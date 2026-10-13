package com.spectrace.labelworkflow.application;

import com.spectrace.labelworkflow.persistence.LabelWorkflowStore;
import com.spectrace.platform.starter.error.ApiException;
import com.spectrace.platform.starter.messaging.Outbox;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Label Workflow publication transaction (G2-M4.2, BR-08; STCN-79).
 *
 * <p>One local transaction enforces the APPROVED state, the recorded APPROVE decision and the
 * current-version guard, supersedes the previous publication for the same product and jurisdiction,
 * marks this version PUBLISHED, records the immutable publication and appends the
 * {@code LabelPublished.v2} event to the outbox with the authoritative business {@code versionNumber}.
 * The event is committed with the state change, so a rollback leaves no event behind.</p>
 */
@Service
public class LabelWorkflowService {

    private final LabelWorkflowStore store;
    private final Outbox outbox;
    private final Clock clock;

    public LabelWorkflowService(LabelWorkflowStore store, Outbox outbox, Clock clock) {
        this.store = store;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * @return the immutable id of the publication record
     * @throws ApiException notFound when the label does not exist or belongs to another organisation (BR-11)
     * @throws ApiException conflict when the label is not APPROVED, has no APPROVE decision, or a newer
     *                      version for the same product and jurisdiction exists
     */
    @Transactional
    public String publish(String organisationId, String labelVersionId, String actorSubject) {
        LabelWorkflowStore.LabelVersionRow label = store.lockLabelVersion(labelVersionId)
                .orElseThrow(() -> ApiException.notFound("Label version " + labelVersionId + " does not exist"));
        if (!label.organisationId().equals(organisationId)) {
            throw ApiException.notFound("Label version " + labelVersionId + " does not exist");
        }
        if (!"APPROVED".equals(label.lifecycleStatus())) {
            throw ApiException.conflict("Only an APPROVED label version can be published");
        }
        if (!store.hasApproveDecision(labelVersionId)) {
            throw ApiException.conflict("Label version " + labelVersionId + " has no APPROVE decision");
        }
        // The version number is the Label business version: it comes from the committed label record
        // and is never derived from the event sequence or parsed out of an identifier.
        int versionNumber = label.versionNumber();
        if (store.hasNewerVersion(label.productId(), label.jurisdictionCode(), versionNumber)) {
            throw ApiException.conflict("A newer label version exists for this product and jurisdiction");
        }
        Instant now = clock.instant();
        store.supersedeCurrent(label.productId(), label.jurisdictionCode(), now);
        if (store.markPublished(labelVersionId, now) != 1) {
            throw ApiException.conflict("Label version " + labelVersionId + " is no longer APPROVED");
        }
        String publicationId = "pub_" + UUID.randomUUID().toString().replace("-", "");
        store.insertPublication(publicationId, labelVersionId, label.productId(), label.jurisdictionCode(),
                versionNumber, now, actorSubject);
        outbox.append("LabelPublished.v2", organisationId, labelVersionId, versionNumber,
                payload(label, versionNumber));
        return publicationId;
    }

    private Map<String, Object> payload(LabelWorkflowStore.LabelVersionRow label, int versionNumber) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", label.productId());
        payload.put("labelVersionId", label.labelVersionId());
        payload.put("versionNumber", versionNumber);
        payload.put("formulaVersionId", label.formulaVersionId());
        payload.put("ruleSetVersionId", label.ruleSetVersionId());
        payload.put("jurisdictionCode", label.jurisdictionCode());
        List<Map<String, Object>> declarations = new ArrayList<>();
        for (LabelWorkflowStore.DeclarationRow declaration : store.declarations(label.labelVersionId())) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("allergenId", declaration.allergenId());
            entry.put("declarationType", declaration.declarationType());
            entry.put("declarationSource", declaration.declarationSource());
            entry.put("displayText", declaration.displayText());
            declarations.add(entry);
        }
        payload.put("declarations", declarations);
        return payload;
    }
}
