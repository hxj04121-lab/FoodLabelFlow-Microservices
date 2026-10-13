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
            SELECT rule_set_version_id, version_number, jurisdiction_code, effective_from, effective_to
            FROM compliance_rule_set
            WHERE jurisdiction_code = ? AND lifecycle_status = 'ACTIVE'
              AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)
            ORDER BY version_number DESC, effective_from DESC, rule_set_version_id
            LIMIT 2
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
        if (rows.size() > 1 && ((Number) rows.get(1).get("version_number")).intValue() == versionNumber) {
            throw new ApiException(HttpStatus.CONFLICT, "RULE_SET_AMBIGUOUS",
                    "More than one effective active rule set has the same version number for jurisdiction "
                            + jurisdiction + ".");
        }
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
