package com.spectrace.labelworkflow.api;

import com.spectrace.labelworkflow.application.RuleSetVersionResolver;
import com.spectrace.labelworkflow.application.LabelWorkflowService;
import com.spectrace.labelworkflow.persistence.LabelWorkflowStore;
import com.spectrace.platform.starter.error.ApiException;
import com.spectrace.platform.starter.security.OrganisationContext;
import com.spectrace.platform.starter.security.OrganisationContexts;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Label Workflow REST API (G2-M4.3, STCN-80; contracts/openapi/label-workflow-v1.yaml).
 *
 * <p>The actor and organisation always come from the validated token (never the request body), and a
 * label of another organisation is reported as absent (BR-11). Draft creation pins the current released
 * formula from the local projection and the active rule set through {@link RuleSetVersionResolver};
 * when no resolver is configured the draft fails closed instead of guessing a rule set.</p>
 */
@RestController
@RequestMapping("/api/labels")
public class LabelWorkflowController {

    private static final String LABEL_OFFICER = "LABEL_OFFICER";
    private static final String APPROVER = "APPROVER";

    private final LabelWorkflowStore store;
    private final LabelWorkflowService service;
    private final ObjectProvider<RuleSetVersionResolver> ruleSets;
    private final Clock clock;

    public LabelWorkflowController(LabelWorkflowStore store, LabelWorkflowService service,
                                   ObjectProvider<RuleSetVersionResolver> ruleSets, Clock clock) {
        this.store = store;
        this.service = service;
        this.ruleSets = ruleSets;
        this.clock = clock;
    }

    public record CreateLabelDraft(String productId, String jurisdictionCode) {
    }

    public record RecordDecision(String decision) {
    }

    /** Frozen decision response (contracts/openapi/label-workflow-v1.yaml). */
    public record LabelDecisionBody(String decisionId, String labelVersionId, String decision, String decidedAt,
                                    String comments) {
    }

    /** Frozen validation response; findings are filled in when the provider returns them. */
    public record LabelValidationBody(String validationRunId, String labelVersionId, String formulaVersionId,
                                      String ruleSetVersionId, String jurisdictionCode, String status,
                                      java.util.List<Object> findings) {
    }

    public record LabelDraft(String labelVersionId, String productId, String formulaVersionId,
                             String ruleSetVersionId, String jurisdictionCode, int versionNumber,
                             String lifecycleStatus) {
    }

    @PostMapping("/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public LabelDraft createDraft(@RequestBody CreateLabelDraft request) {
        OrganisationContext caller = OrganisationContexts.current();
        if (!caller.roles().contains(LABEL_OFFICER)) {
            throw ApiException.forbidden();
        }
        if (request == null || request.productId() == null || request.productId().isBlank()
                || request.jurisdictionCode() == null || request.jurisdictionCode().isBlank()) {
            throw ApiException.invalid("productId and jurisdictionCode are required");
        }
        String formulaVersionId = store.currentFormulaVersion(caller.organisationId(), request.productId())
                .orElseThrow(() -> ApiException.conflict(
                        "No released formula is known for product " + request.productId()))
                .formulaVersionId();
        String ruleSetVersionId = ruleSets
                .getIfAvailable(() -> (organisationId, productId, jurisdictionCode) -> {
                    throw new IllegalStateException("No active rule-set source is configured for product "
                            + productId + " in jurisdiction " + jurisdictionCode
                            + " (organisation " + organisationId + ")");
                })
                .resolve(caller.organisationId(), request.productId(), request.jurisdictionCode());
        String labelVersionId = "lbl_" + UUID.randomUUID().toString().replace("-", "");
        int versionNumber = store.nextVersionNumber(request.productId(), request.jurisdictionCode());
        try {
            store.insertDraft(labelVersionId, caller.organisationId(), request.productId(), versionNumber,
                    formulaVersionId, ruleSetVersionId, request.jurisdictionCode(), caller.subject(), clock.instant());
        } catch (DuplicateKeyException conflict) {
            throw ApiException.conflict("Another label version was created concurrently for this product and jurisdiction");
        }
        return new LabelDraft(labelVersionId, request.productId(), formulaVersionId, ruleSetVersionId,
                request.jurisdictionCode(), versionNumber, "DRAFT");
    }

    @GetMapping("/{labelVersionId}")
    public LabelDraft getLabelVersion(@PathVariable String labelVersionId) {
        OrganisationContext caller = OrganisationContexts.current();
        LabelWorkflowStore.LabelVersionRow row = store.labelVersion(labelVersionId)
                .orElseThrow(() -> ApiException.notFound("Label version " + labelVersionId + " does not exist"));
        if (!row.organisationId().equals(caller.organisationId())) {
            throw ApiException.notFound("Label version " + labelVersionId + " does not exist");
        }
        return new LabelDraft(row.labelVersionId(), row.productId(), row.formulaVersionId(), row.ruleSetVersionId(),
                row.jurisdictionCode(), row.versionNumber(), row.lifecycleStatus());
    }

    @PostMapping("/{labelVersionId}/submit")
    public LabelDraft submit(@PathVariable String labelVersionId) {
        OrganisationContext caller = OrganisationContexts.current();
        if (!caller.roles().contains(LABEL_OFFICER)) {
            throw ApiException.forbidden();
        }
        return draft(service.submit(caller.organisationId(), labelVersionId, caller.subject()));
    }

    @PostMapping("/{labelVersionId}/decisions")
    public LabelDecisionBody recordDecision(@PathVariable String labelVersionId,
                                            @RequestBody(required = false) RecordDecision request) {
        OrganisationContext caller = OrganisationContexts.current();
        if (!caller.roles().contains(APPROVER)) {
            throw ApiException.forbidden();
        }
        String decision = request == null ? null : request.decision();
        var outcome = service.decide(caller.organisationId(), labelVersionId, decision, caller.subject());
        return new LabelDecisionBody(outcome.decisionId(), labelVersionId, outcome.decision(),
                outcome.decidedAt().toString(), null);
    }

    @PostMapping("/{labelVersionId}/validations")
    @ResponseStatus(HttpStatus.CREATED)
    public LabelValidationBody validate(@PathVariable String labelVersionId,
                                        @RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        OrganisationContext caller = OrganisationContexts.current();
        if (!caller.roles().contains(LABEL_OFFICER)) {
            throw ApiException.forbidden();
        }
        var label = store.labelVersion(labelVersionId);
        var outcome = service.validate(caller.organisationId(), labelVersionId, authorization, correlationId);
        var row = label.orElse(null);
        return new LabelValidationBody(outcome.validationRunId(), labelVersionId,
                row == null ? null : row.formulaVersionId(), row == null ? null : row.ruleSetVersionId(),
                row == null ? null : row.jurisdictionCode(), outcome.status(), java.util.List.of());
    }

    private static LabelDraft draft(LabelWorkflowStore.LabelVersionRow row) {
        return new LabelDraft(row.labelVersionId(), row.productId(), row.formulaVersionId(), row.ruleSetVersionId(),
                row.jurisdictionCode(), row.versionNumber(), row.lifecycleStatus());
    }
}
