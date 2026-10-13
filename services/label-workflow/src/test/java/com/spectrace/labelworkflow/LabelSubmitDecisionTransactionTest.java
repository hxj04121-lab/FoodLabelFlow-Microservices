package com.spectrace.labelworkflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spectrace.labelworkflow.application.LabelWorkflowService;
import com.spectrace.platform.starter.error.ApiException;
import com.spectrace.platform.test.MySqlTestcontainers;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** STCN-79 / G2-M4.2: the submit and decision transactions against real MySQL (BR-05, BR-06, BR-09). */
@Import(MySqlTestcontainers.class)
@SpringBootTest(properties = "spectrace.test.database=label_workflow")
class LabelSubmitDecisionTransactionTest {

    private static final String ORG = "org-manufacturer-001";

    @Autowired JdbcTemplate jdbc;
    @Autowired LabelWorkflowService service;

    @BeforeEach
    void clearLabelState() {
        jdbc.update("DELETE FROM label_review_task");
        jdbc.update("DELETE FROM label_decision");
        jdbc.update("DELETE FROM label_publication");
        jdbc.update("DELETE FROM label_validation_record");
        jdbc.update("DELETE FROM label_allergen_declaration");
        jdbc.update("DELETE FROM label_version");
    }

    private void seedDraft(String id, String createdBy) {
        Instant now = Instant.parse("2026-10-13T09:00:00Z");
        jdbc.update("""
                INSERT INTO label_version (label_version_id, organisation_id, product_id, version_number,
                    formula_version_id, rule_set_version_id, jurisdiction_code, lifecycle_status,
                    created_by_subject, created_at, updated_at)
                VALUES (?, ?, 'product_demo_1', 1, 'formula_demo_v1', 'ruleset_us_v1', 'US', 'DRAFT', ?, ?, ?)
                """, id, ORG, createdBy, Timestamp.from(now), Timestamp.from(now));
    }

    private void seedValidation(String labelVersionId, String result) {
        jdbc.update("""
                INSERT INTO label_validation_record (validation_run_id, label_version_id, formula_version_id,
                    rule_set_version_id, jurisdiction_code, result_status, draft_revision, recorded_at)
                VALUES (?, ?, 'formula_demo_v1', 'ruleset_us_v1', 'US', ?, 1, ?)
                """, "run_" + labelVersionId, labelVersionId, result, Timestamp.from(Instant.parse("2026-10-13T09:05:00Z")));
    }

    private String status(String labelVersionId) {
        return jdbc.queryForObject("SELECT lifecycle_status FROM label_version WHERE label_version_id = ?",
                String.class, labelVersionId);
    }

    @Test
    void submitWithoutPassedValidationIsRejectedAndLeavesTheDraftUntouched() {
        seedDraft("lbl_s1", "maker");
        seedValidation("lbl_s1", "FAILED");

        assertThatThrownBy(() -> service.submit(ORG, "lbl_s1", "maker"))
                .isInstanceOf(ApiException.class).hasMessageContaining("PASSED");

        assertThat(status("lbl_s1")).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM label_review_task", Integer.class)).isZero();
    }

    @Test
    void submitWithPassedValidationCreatesOneOpenReviewTask() {
        seedDraft("lbl_s2", "maker");
        seedValidation("lbl_s2", "PASSED");

        String reviewTaskId = service.submit(ORG, "lbl_s2", "maker");

        assertThat(reviewTaskId).startsWith("task_");
        assertThat(status("lbl_s2")).isEqualTo("PENDING_REVIEW");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM label_review_task WHERE label_version_id = 'lbl_s2' AND status = 'OPEN'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void approvalByAnotherActorMovesToApprovedAndClosesTheTask() {
        seedDraft("lbl_s3", "maker");
        seedValidation("lbl_s3", "PASSED");
        service.submit(ORG, "lbl_s3", "maker");

        String target = service.decide(ORG, "lbl_s3", "APPROVE", "checker");

        assertThat(target).isEqualTo("APPROVED");
        assertThat(status("lbl_s3")).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM label_review_task WHERE label_version_id = 'lbl_s3' AND status = 'OPEN'
                """, Integer.class)).isZero();
    }

    @Test
    void theCreatorCannotApproveTheirOwnLabelVersion() {
        seedDraft("lbl_s4", "maker");
        seedValidation("lbl_s4", "PASSED");
        service.submit(ORG, "lbl_s4", "maker");

        assertThatThrownBy(() -> service.decide(ORG, "lbl_s4", "APPROVE", "maker"))
                .isInstanceOf(ApiException.class).hasMessageContaining("cannot approve");

        assertThat(status("lbl_s4")).isEqualTo("PENDING_REVIEW");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM label_decision WHERE label_version_id = 'lbl_s4'
                """, Integer.class)).isZero();
    }

    @Test
    void requestChangesReturnsTheVersionToDraft() {
        seedDraft("lbl_s5", "maker");
        seedValidation("lbl_s5", "PASSED");
        service.submit(ORG, "lbl_s5", "maker");

        String target = service.decide(ORG, "lbl_s5", "request_changes", "checker");

        assertThat(target).isEqualTo("DRAFT");
        assertThat(status("lbl_s5")).isEqualTo("DRAFT");
    }

    @Test
    void aDecisionWithoutAnOpenReviewTaskIsRejected() {
        seedDraft("lbl_s6", "maker");
        jdbc.update("""
                UPDATE label_version SET lifecycle_status = 'PENDING_REVIEW' WHERE label_version_id = 'lbl_s6'
                """);

        assertThatThrownBy(() -> service.decide(ORG, "lbl_s6", "APPROVE", "checker"))
                .isInstanceOf(ApiException.class).hasMessageContaining("review task");
    }
}
