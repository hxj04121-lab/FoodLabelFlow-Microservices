package com.spectrace.labelworkflow.application;

import com.spectrace.labelworkflow.persistence.LabelWorkflowStore;
import com.spectrace.labelworkflow.validation.ValidationClient;
import com.spectrace.platform.starter.error.ApiException;
import com.spectrace.platform.starter.messaging.Outbox;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private final ValidationClient validations;
    private final Clock clock;

    /** Frozen success shape for a decision (contracts/openapi/label-workflow-v1.yaml). */
    public record DecisionOutcome(String decisionId, String labelVersionId, String decision, Instant decidedAt,
                                  String comments) {
    }

    /** Frozen success shape for a validation run. */
    public record ValidationOutcome(String validationRunId, String status,
                                    java.util.List<ValidationClient.ValidationFinding> findings) {
    }

    /** Frozen success shape for a publication (contracts/openapi/label-workflow-v1.yaml). */
    public record PublicationOutcome(String publicationRecordId, Instant publishedAt) {
    }

    public record DeclarationInput(String allergenId, String declarationType, String declarationSource,
                                   String displayText) { }

    public LabelWorkflowService(LabelWorkflowStore store, Outbox outbox, ValidationClient validations, Clock clock) {
        this.store = store;
        this.outbox = outbox;
        this.validations = validations;
        this.clock = clock;
    }

    /**
     * @return the immutable id of the publication record
     * @throws ApiException notFound when the label is absent; forbidden when another organisation owns it (BR-11)
     * @throws ApiException conflict when the label is not APPROVED, has no APPROVE decision, or a newer
     *                      version for the same product and jurisdiction exists
     */
    @Transactional
    public PublicationOutcome publish(String organisationId, String labelVersionId, String actorSubject) {
        LabelWorkflowStore.LabelVersionRow label = store.lockLabelVersion(labelVersionId)
                .orElseThrow(() -> ApiException.notFound("Label version " + labelVersionId + " does not exist"));
        if (!label.organisationId().equals(organisationId)) {
            // Agreed policy: another organisation private resource is 403; 404 is reserved for absence.
            throw ApiException.forbidden();
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
        return new PublicationOutcome(publicationId, now);
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

    /**
     * Submit transaction (G2-M4.2, BR-05): a draft with a PASSED validation record moves to
     * PENDING_REVIEW and gains exactly one open review task, atomically.
     *
     * @return the id of the created review task
     */
    @Transactional
    public LabelWorkflowStore.LabelVersionRow submit(String organisationId, String labelVersionId,
                                                     String actorSubject) {
        LabelWorkflowStore.LabelVersionRow label = requireLabel(organisationId, labelVersionId);
        if (!"DRAFT".equals(label.lifecycleStatus())) {
            throw ApiException.conflict("Only a DRAFT label version can be submitted for review");
        }
        if (!store.hasPassedValidation(label)) {
            throw ApiException.conflict("A PASSED validation record for the current draft revision is required");
        }
        // Baseline oracle: a newer draft created after the service precheck invalidates the submit.
        if (store.hasNewerVersion(label.productId(), label.jurisdictionCode(), label.versionNumber())) {
            throw ApiException.conflict("A newer label version exists for this product and jurisdiction");
        }
        Instant now = clock.instant();
        if (store.transition(labelVersionId, "DRAFT", "PENDING_REVIEW", now) != 1) {
            throw ApiException.conflict("Label version " + labelVersionId + " is no longer a DRAFT");
        }
        String reviewTaskId = "task_" + UUID.randomUUID().toString().replace("-", "");
        store.insertReviewTask(reviewTaskId, labelVersionId, now);
        return store.labelVersion(labelVersionId).orElseThrow();
    }

    /**
     * Decision transaction (G2-M4.2, BR-06 and BR-09): requires an open review task; APPROVE moves the
     * version to APPROVED, REQUEST_CHANGES returns it to DRAFT, REJECT closes it. BR-06 only forbids the
     * creator from approving - the wider baseline "creator may never decide" rule is not carried over.
     */
    @Transactional
    public DecisionOutcome decide(String organisationId, String labelVersionId, String decision,
                                  String comments, String actorSubject) {
        LabelWorkflowStore.LabelVersionRow label = requireLabel(organisationId, labelVersionId);
        if (!"PENDING_REVIEW".equals(label.lifecycleStatus())) {
            throw ApiException.conflict("Only a label version pending review can receive a decision");
        }
        String reviewTaskId = store.openReviewTask(labelVersionId)
                .orElseThrow(() -> ApiException.conflict("No open review task for " + labelVersionId));
        String normalized = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("APPROVE", "REQUEST_CHANGES", "REJECT").contains(normalized)) {
            throw ApiException.invalid("decision must be APPROVE, REQUEST_CHANGES or REJECT");
        }
        if (comments != null && comments.length() > 1000) {
            throw ApiException.invalid("comments must be at most 1000 characters");
        }
        if ("APPROVE".equals(normalized) && actorSubject.equals(label.createdBySubject())) {
            throw ApiException.conflict("The creator of a label version cannot approve it");
        }
        Instant now = clock.instant();
        String decisionId = "dec_" + UUID.randomUUID().toString().replace("-", "");
        store.insertDecision(decisionId, labelVersionId, normalized, actorSubject, comments, now);
        String target = switch (normalized) {
            case "APPROVE" -> "APPROVED";
            case "REJECT" -> "REJECTED";
            default -> "DRAFT";
        };
        if (store.transition(labelVersionId, "PENDING_REVIEW", target, now) != 1) {
            throw ApiException.conflict("Label version " + labelVersionId + " is no longer pending review");
        }
        store.resolveReviewTask(reviewTaskId, now);
        return new DecisionOutcome(decisionId, labelVersionId, normalized, now, comments);
    }

    /** Replaces a draft's declarations and advances its private revision in one local transaction. */
    @Transactional
    public List<LabelWorkflowStore.DeclarationRow> replaceDeclarations(String organisationId, String labelVersionId,
                                                                       List<DeclarationInput> declarations) {
        LabelWorkflowStore.LabelVersionRow label = requireLabel(organisationId, labelVersionId);
        if (!"DRAFT".equals(label.lifecycleStatus())) {
            throw ApiException.conflict("Declarations can only be changed while the label is a DRAFT");
        }
        if (declarations == null) {
            throw ApiException.invalid("declarations is required");
        }
        List<LabelWorkflowStore.DeclarationRow> rows = new ArrayList<>();
        Set<String> allergenIds = new java.util.HashSet<>();
        for (DeclarationInput declaration : declarations) {
            if (declaration == null || declaration.allergenId() == null || declaration.allergenId().isBlank()
                    || !"CONTAINS".equals(declaration.declarationType())
                    || declaration.declarationSource() == null
                    || !Set.of("MIGRATED_PUBLIC_LABEL", "FORMULA_DERIVED", "SYSTEM_PROPOSED", "USER_ENTERED")
                    .contains(declaration.declarationSource())
                    || declaration.displayText() != null && declaration.displayText().length() > 300) {
                throw ApiException.invalid("Each declaration must match the Label declaration contract");
            }
            if (!allergenIds.add(declaration.allergenId())) {
                throw ApiException.invalid("Each allergen can be declared only once");
            }
            rows.add(new LabelWorkflowStore.DeclarationRow(declaration.allergenId(), declaration.declarationType(),
                    declaration.declarationSource(), declaration.displayText()));
        }
        if (store.advanceDraftRevision(labelVersionId, clock.instant()) != 1) {
            throw ApiException.conflict("The label is no longer a DRAFT");
        }
        store.replaceDeclarations(labelVersionId, rows);
        return store.declarations(labelVersionId);
    }

    /** Returns the tenant-bound immutable decision history. */
    @Transactional(readOnly = true)
    public List<DecisionOutcome> decisions(String organisationId, String labelVersionId) {
        LabelWorkflowStore.LabelVersionRow label = store.labelVersion(labelVersionId)
                .orElseThrow(() -> ApiException.notFound("Label version " + labelVersionId + " does not exist"));
        if (!label.organisationId().equals(organisationId)) {
            throw ApiException.forbidden();
        }
        return store.decisions(labelVersionId).stream()
                .map(row -> new DecisionOutcome(row.decisionId(), row.labelVersionId(), row.decision(),
                        row.decidedAt(), row.comments()))
                .toList();
    }

    /** Resolves the current publication and enforces tenant ownership before returning it. */
    @Transactional(readOnly = true)
    public LabelWorkflowStore.LabelVersionRow currentPublishedLabel(String organisationId, String productId,
                                                                    String jurisdictionCode) {
        LabelWorkflowStore.LabelVersionRow label = store.currentPublishedLabel(productId, jurisdictionCode)
                .orElseThrow(() -> ApiException.notFound("No current published label exists for this product and jurisdiction"));
        if (!label.organisationId().equals(organisationId)) {
            throw ApiException.forbidden();
        }
        return label;
    }

    private LabelWorkflowStore.LabelVersionRow requireLabel(String organisationId, String labelVersionId) {
        LabelWorkflowStore.LabelVersionRow label = store.lockLabelVersion(labelVersionId)
                .orElseThrow(() -> ApiException.notFound("Label version " + labelVersionId + " does not exist"));
        if (!label.organisationId().equals(organisationId)) {
            throw ApiException.forbidden();
        }
        return label;
    }

    /**
     * Asks Compliance to validate the exact label/formula/rule-set tuple and stores the answer locally.
     * The idempotency key is derived from the label version and draft revision, so a retry or a repeated
     * call resolves to the same stored run. An unavailable validation fails closed (the caller sees an
     * error, never a pass).
     */
    @Transactional
    public ValidationOutcome validate(String organisationId, String labelVersionId, String authorization,
                                      String correlationId) {
        LabelWorkflowStore.LabelVersionRow label = requireLabel(organisationId, labelVersionId);
        if (!"DRAFT".equals(label.lifecycleStatus())) {
            throw ApiException.conflict("Only a DRAFT label version can be validated");
        }
        // The revision is private and server-owned: never read from the client, never exposed.
        int draftRevision = label.draftRevision();
        if (draftRevision < 1) {
            throw ApiException.conflict("The label has no owned draft revision; refusing to guess one");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("organisationId", organisationId);
        request.put("labelVersionId", labelVersionId);
        request.put("versionNumber", label.versionNumber());
        request.put("formulaVersionId", label.formulaVersionId());
        request.put("ruleSetVersionId", label.ruleSetVersionId());
        // The frozen provider contract names this field jurisdiction, not jurisdictionCode.
        request.put("jurisdiction", label.jurisdictionCode());
        request.put("draftRevision", draftRevision);
        List<Map<String, Object>> declarations = new ArrayList<>();
        for (LabelWorkflowStore.DeclarationRow declaration : store.declarations(labelVersionId)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("allergenId", declaration.allergenId());
            entry.put("declarationType", declaration.declarationType());
            declarations.add(entry);
        }
        request.put("declarations", declarations);
        ValidationClient.ValidationResult result = validations.validate(
                labelVersionId + ":" + draftRevision, request, authorization, correlationId);
        if (result == null || result.validationRunId() == null || result.validationRunId().isBlank()
                || !Set.of("PASSED", "FAILED").contains(result.resultStatus()) || result.findings() == null) {
            throw new ValidationClient.ValidationUnavailableException(
                    "Compliance did not return a complete validation result");
        }
        String status = result.resultStatus();
        boolean blocking = result.findings().stream().anyMatch(ValidationClient.ValidationFinding::blocking);
        if ("PASSED".equals(status) && blocking || "FAILED".equals(status) && !blocking) {
            throw new ValidationClient.ValidationUnavailableException(
                    "Compliance returned an inconsistent status and findings tuple");
        }
        java.util.List<ValidationClient.ValidationFinding> findings = List.copyOf(result.findings());
        String validationRunId = result.validationRunId();
        store.insertValidationRecord(validationRunId,
                labelVersionId, label.formulaVersionId(), label.ruleSetVersionId(), label.jurisdictionCode(),
                status, draftRevision, clock.instant());
        return new ValidationOutcome(validationRunId, status, findings);
    }
}
