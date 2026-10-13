package com.spectrace.labelworkflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spectrace.labelworkflow.application.LabelWorkflowService;
import com.spectrace.platform.starter.error.ApiException;
import com.spectrace.platform.test.MySqlTestcontainers;
import java.time.Instant;
import java.sql.Timestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** STCN-79 / G2-M4.2: the publication transaction against real MySQL (BR-08). */
@Import(MySqlTestcontainers.class)
@SpringBootTest(properties = "spectrace.test.database=label_workflow")
class LabelPublicationTransactionTest {

    private static final String ORG = "org-manufacturer-001";

    @Autowired JdbcTemplate jdbc;
    @Autowired LabelWorkflowService service;

    /** MySqlTestcontainers shares one database across the module run, so each test starts from the label tables cleared. */
    @BeforeEach
    void clearLabelState() {
        jdbc.update("DELETE FROM label_publication");
        jdbc.update("DELETE FROM label_decision");
        jdbc.update("DELETE FROM label_validation_record");
        jdbc.update("DELETE FROM label_allergen_declaration");
        jdbc.update("DELETE FROM label_version");
        jdbc.update("DELETE FROM outbox WHERE event_type = 'LabelPublished.v2'");
    }

    private void seedLabel(String id, int version, String status) {
        Instant now = Instant.parse("2026-10-13T08:00:00Z");
        jdbc.update("""
                INSERT INTO label_version (label_version_id, organisation_id, product_id, version_number,
                    formula_version_id, rule_set_version_id, jurisdiction_code, lifecycle_status,
                    created_by_subject, created_at, updated_at)
                VALUES (?, ?, 'product_demo_1', ?, 'formula_demo_v1', 'ruleset_us_v1', 'US', ?, 'maker', ?, ?)
                """, id, ORG, version, status, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO label_allergen_declaration (label_version_id, allergen_id, declaration_type,
                    declaration_source, display_text)
                VALUES (?, 'allergen-milk', 'CONTAINS', 'FORMULA_DERIVED', 'Contains: Milk')
                """, id);
    }

    private void seedDecision(String id, String decision) {
        jdbc.update("""
                INSERT INTO label_decision (label_decision_id, label_version_id, decision, actor_subject, decided_at)
                VALUES (?, ?, ?, 'approver', ?)
                """, "dec_" + id, id, decision, Timestamp.from(Instant.parse("2026-10-13T08:05:00Z")));
    }

    private long v2OutboxRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE event_type = 'LabelPublished.v2'",
                Long.class);
    }

    @Test
    void publishesAnApprovedLabelAndEmitsTheV2EventInTheSameTransaction() {
        seedLabel("lbl_pub_1", 1, "APPROVED");
        seedDecision("lbl_pub_1", "APPROVE");

        var outcome = service.publish(ORG, "lbl_pub_1", "publisher");

        assertThat(outcome.publicationRecordId()).startsWith("pub_");
        assertThat(outcome.publishedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT lifecycle_status FROM label_version WHERE label_version_id = 'lbl_pub_1'",
                String.class)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT version_number FROM label_publication WHERE label_version_id = 'lbl_pub_1'",
                Integer.class)).isEqualTo(1);
        String envelope = jdbc.queryForObject("""
                SELECT envelope FROM outbox WHERE event_type = 'LabelPublished.v2' AND aggregate_id = 'lbl_pub_1'
                """, String.class);
        // Compare without whitespace so the assertion does not depend on the mapper's formatting.
        assertThat(envelope.replaceAll("\\s+", "")).contains("\"versionNumber\":1")
                .contains("\"labelVersionId\":\"lbl_pub_1\"")
                .contains("\"eventType\":\"LabelPublished.v2\"");
    }

    @Test
    void rejectsADraftThatIsNotApprovedAndWritesNoEvent() {
        seedLabel("lbl_draft_1", 1, "DRAFT");

        assertThatThrownBy(() -> service.publish(ORG, "lbl_draft_1", "publisher"))
                .isInstanceOf(ApiException.class).hasMessageContaining("APPROVED");
        assertThat(v2OutboxRows()).isZero();
    }

    @Test
    void rejectsAnApprovedLabelWithoutAnApproveDecision() {
        seedLabel("lbl_nodec_1", 1, "APPROVED");

        assertThatThrownBy(() -> service.publish(ORG, "lbl_nodec_1", "publisher"))
                .isInstanceOf(ApiException.class).hasMessageContaining("APPROVE decision");
        assertThat(v2OutboxRows()).isZero();
    }

    @Test
    void rejectsALabelOfAnotherOrganisationWithoutRevealingIt() {
        seedLabel("lbl_other_1", 1, "APPROVED");
        seedDecision("lbl_other_1", "APPROVE");

        assertThatThrownBy(() -> service.publish("org-manufacturer-002", "lbl_other_1", "publisher"))
                .isInstanceOfSatisfying(ApiException.class,
                        thrown -> assertThat(thrown.code()).isEqualTo("AUTHORIZATION_DENIED"));
        assertThat(v2OutboxRows()).isZero();
    }

    @Test
    void publishingANewerVersionSupersedesThePreviousPublication() {
        seedLabel("lbl_v1", 1, "APPROVED");
        seedDecision("lbl_v1", "APPROVE");
        service.publish(ORG, "lbl_v1", "publisher");
        seedLabel("lbl_v2", 2, "APPROVED");
        seedDecision("lbl_v2", "APPROVE");

        service.publish(ORG, "lbl_v2", "publisher");

        assertThat(jdbc.queryForList("""
                SELECT label_version_id FROM label_version
                WHERE product_id = 'product_demo_1' AND jurisdiction_code = 'US' AND lifecycle_status = 'PUBLISHED'
                """, String.class)).containsExactly("lbl_v2");
        assertThat(jdbc.queryForObject("SELECT lifecycle_status FROM label_version WHERE label_version_id = 'lbl_v1'",
                String.class)).isEqualTo("SUPERSEDED");
        assertThat(v2OutboxRows()).isEqualTo(2);
    }
}
