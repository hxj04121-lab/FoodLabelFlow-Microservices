package com.spectrace.labelworkflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spectrace.labelworkflow.application.LabelWorkflowService;
import com.spectrace.platform.starter.error.ApiException;
import com.spectrace.platform.test.MySqlTestcontainers;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * STCN-105 / G3-M4.1: the G0 equivalence-oracle inventory, ported from the baseline stored
 * procedures to the Java transactions. Each test carries the baseline oracle name it replaces
 * (docs/swe5001/security/label-workflow-oracle-inventory.md).
 */
@Import(MySqlTestcontainers.class)
@SpringBootTest(properties = "spectrace.test.database=label_workflow")
class LabelWorkflowEquivalenceTest {

    private static final String ORG = "org-manufacturer-001";

    @Autowired JdbcTemplate jdbc;
    @Autowired LabelWorkflowService service;

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM label_review_task");
        jdbc.update("DELETE FROM label_decision");
        jdbc.update("DELETE FROM label_publication");
        jdbc.update("DELETE FROM label_validation_record");
        jdbc.update("DELETE FROM label_allergen_declaration");
        jdbc.update("DELETE FROM label_version");
    }

    private void seed(String id, int version, String status, String creator) {
        Instant now = Instant.parse("2026-10-13T12:00:00Z");
        jdbc.update("""
                INSERT INTO label_version (label_version_id, organisation_id, product_id, version_number,
                    formula_version_id, rule_set_version_id, jurisdiction_code, lifecycle_status,
                    created_by_subject, created_at, updated_at)
                VALUES (?, ?, 'product_eq_1', ?, 'formula_eq_v1', 'ruleset_us_v1', 'US', ?, ?, ?, ?)
                """, id, ORG, version, status, creator, Timestamp.from(now), Timestamp.from(now));
    }

    private void passed(String labelVersionId) {
        jdbc.update("""
                INSERT INTO label_validation_record (validation_run_id, label_version_id, formula_version_id,
                    rule_set_version_id, jurisdiction_code, result_status, draft_revision, recorded_at)
                VALUES (?, ?, 'formula_eq_v1', 'ruleset_us_v1', 'US', 'PASSED', 1, ?)
                """, "run_" + labelVersionId, labelVersionId, Timestamp.from(Instant.parse("2026-10-13T12:01:00Z")));
    }

    private String status(String id) {
        return jdbc.queryForObject("SELECT lifecycle_status FROM label_version WHERE label_version_id = ?",
                String.class, id);
    }

    @Test
    @DisplayName("sp_submit_label_for_review: rejectsSecondSubmitAfterPendingReview")
    void rejectsSecondSubmitAfterPendingReview() {
        seed("eq_v1", 1, "DRAFT", "maker");
        passed("eq_v1");
        service.submit(ORG, "eq_v1", "maker");

        assertThatThrownBy(() -> service.submit(ORG, "eq_v1", "maker"))
                .isInstanceOf(ApiException.class).hasMessageContaining("DRAFT");

        assertThat(status("eq_v1")).isEqualTo("PENDING_REVIEW");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM label_review_task WHERE label_version_id = 'eq_v1'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("sp_submit_label_for_review: rejectsSubmitWhenNewerDraftWinsRace")
    void rejectsSubmitWhenANewerDraftExists() {
        seed("eq_v2", 1, "DRAFT", "maker");
        passed("eq_v2");
        seed("eq_v3", 2, "DRAFT", "maker"); // a newer draft appeared after the precheck

        assertThatThrownBy(() -> service.submit(ORG, "eq_v2", "maker"))
                .isInstanceOf(ApiException.class).hasMessageContaining("newer label version");

        assertThat(status("eq_v2")).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM label_review_task WHERE label_version_id = 'eq_v2'
                """, Integer.class)).isZero();
    }

    @Test
    @DisplayName("sp_publish_label: databaseGuardRejectsPublishingApprovedHistoricalVersion")
    void rejectsPublishingAHistoricalApprovedVersion() {
        seed("eq_hist_v1", 1, "APPROVED", "maker");
        jdbc.update("""
                INSERT INTO label_decision (label_decision_id, label_version_id, decision, actor_subject, decided_at)
                VALUES ('dec_hist', 'eq_hist_v1', 'APPROVE', 'checker', ?)
                """, Timestamp.from(Instant.parse("2026-10-13T12:02:00Z")));
        seed("eq_hist_v2", 2, "DRAFT", "maker"); // a newer version already exists

        assertThatThrownBy(() -> service.publish(ORG, "eq_hist_v1", "publisher"))
                .isInstanceOf(ApiException.class).hasMessageContaining("newer label version");

        assertThat(status("eq_hist_v1")).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM label_publication", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE event_type = 'LabelPublished.v2'",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("sp_publish_label: publishesApprovedLabelAndSupersedesPreviousPublishedLabel")
    void publishSupersedesThePreviousPublicationAndEmitsOneEvent() {
        seed("eq_pub_v1", 1, "APPROVED", "maker");
        jdbc.update("""
                INSERT INTO label_decision (label_decision_id, label_version_id, decision, actor_subject, decided_at)
                VALUES ('dec_pub1', 'eq_pub_v1', 'APPROVE', 'checker', ?)
                """, Timestamp.from(Instant.parse("2026-10-13T12:03:00Z")));
        service.publish(ORG, "eq_pub_v1", "publisher");
        seed("eq_pub_v2", 2, "APPROVED", "maker");
        jdbc.update("""
                INSERT INTO label_decision (label_decision_id, label_version_id, decision, actor_subject, decided_at)
                VALUES ('dec_pub2', 'eq_pub_v2', 'APPROVE', 'checker', ?)
                """, Timestamp.from(Instant.parse("2026-10-13T12:04:00Z")));

        service.publish(ORG, "eq_pub_v2", "publisher");

        assertThat(status("eq_pub_v1")).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForList("""
                SELECT label_version_id FROM label_version WHERE lifecycle_status = 'PUBLISHED'
                """, String.class)).containsExactly("eq_pub_v2");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox WHERE event_type = 'LabelPublished.v2' AND aggregate_id = 'eq_pub_v2'
                """, Integer.class)).isEqualTo(1);
    }
}
