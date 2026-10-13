package com.spectrace.compliance.ruleset;

import com.spectrace.platform.starter.error.ApiException;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Resolves the exact effective active rule set from Compliance's own authoritative table. */
@Service
public class EffectiveRuleSetService {

    private static final String FIND_EFFECTIVE = """
            SELECT ruleSet.rule_set_version_id, ruleSet.version_number, ruleSet.jurisdiction_code,
                ruleSet.effective_from, ruleSet.effective_to
            FROM compliance_effective_rule_set currentRuleSet
            JOIN compliance_rule_set ruleSet
              ON ruleSet.jurisdiction_code = currentRuleSet.jurisdiction_code
             AND ruleSet.rule_set_version_id = currentRuleSet.rule_set_version_id
            WHERE currentRuleSet.jurisdiction_code = ? AND ruleSet.lifecycle_status = 'ACTIVE'
              AND ruleSet.effective_from <= ? AND (ruleSet.effective_to IS NULL OR ruleSet.effective_to >= ?)
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public EffectiveRuleSetService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public EffectiveRuleSet resolve(String jurisdiction) {
        if (jurisdiction == null || jurisdiction.isBlank() || jurisdiction.length() > 16) {
            throw ApiException.invalid("jurisdiction must contain 1 to 16 characters.");
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        List<Map<String, Object>> rows = jdbc.queryForList(FIND_EFFECTIVE, jurisdiction, Date.valueOf(today),
                Date.valueOf(today));
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RULE_SET_UNAVAILABLE",
                    "No effective active rule set is configured for jurisdiction " + jurisdiction + ".");
        }
        Map<String, Object> selected = rows.getFirst();
        int versionNumber = ((Number) selected.get("version_number")).intValue();
        return new EffectiveRuleSet((String) selected.get("rule_set_version_id"), versionNumber,
                (String) selected.get("jurisdiction_code"), toLocalDate(selected.get("effective_from")),
                toLocalDate(selected.get("effective_to")));
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) return null;
        if (value instanceof Date date) return date.toLocalDate();
        if (value instanceof LocalDate date) return date;
        throw new IllegalStateException("Compliance returned an unsupported effective-date value");
    }
}
