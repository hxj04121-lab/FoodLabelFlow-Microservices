package com.spectrace.compliance.ruleset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.spectrace.platform.starter.error.ApiException;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class EffectiveRuleSetServiceTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-13");
    private JdbcTemplate jdbc;
    private EffectiveRuleSetService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new EffectiveRuleSetService(jdbc, Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void selectsTheHighestEffectiveVersionAndReturnsItsPinnedIdentity() {
        when(jdbc.queryForList(anyString(), eq("US"), eq(Date.valueOf(TODAY)), eq(Date.valueOf(TODAY))))
                .thenReturn(List.of(row("ruleset-us-v2", 2, "US", TODAY, null),
                        row("ruleset-us-v1", 1, "US", LocalDate.parse("2026-01-01"), null)));

        assertThat(service.resolve("US")).isEqualTo(new EffectiveRuleSet("ruleset-us-v2", 2, "US", TODAY, null));
    }

    @Test
    void missingEffectiveVersionFailsClosed() {
        when(jdbc.queryForList(anyString(), eq("CA"), eq(Date.valueOf(TODAY)), eq(Date.valueOf(TODAY))))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.resolve("CA"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                .hasMessageContaining("No effective active rule set");
    }

    @Test
    void tiedHighestVersionsFailClosedInsteadOfChoosingById() {
        when(jdbc.queryForList(anyString(), eq("US"), eq(Date.valueOf(TODAY)), eq(Date.valueOf(TODAY))))
                .thenReturn(List.of(row("ruleset-us-v2a", 2, "US", TODAY, null),
                        row("ruleset-us-v2b", 2, "US", TODAY, null)));

        assertThatThrownBy(() -> service.resolve("US"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("same version number");
    }

    @Test
    void invalidJurisdictionIsRejectedBeforeDatabaseLookup() {
        assertThatThrownBy(() -> service.resolve(" ")).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(jdbc);
    }

    private static Map<String, Object> row(String id, int version, String jurisdiction,
                                            LocalDate from, LocalDate to) {
        Map<String, Object> values = new HashMap<>();
        values.put("rule_set_version_id", id);
        values.put("version_number", version);
        values.put("jurisdiction_code", jurisdiction);
        values.put("effective_from", Date.valueOf(from));
        values.put("effective_to", to == null ? null : Date.valueOf(to));
        return values;
    }
}
