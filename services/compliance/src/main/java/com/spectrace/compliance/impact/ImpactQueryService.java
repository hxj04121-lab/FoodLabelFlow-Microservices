package com.spectrace.compliance.impact;

import com.spectrace.compliance.authorization.ValidationAuthorizationPort.Actor;
import com.spectrace.platform.starter.correlation.CorrelationId;
import com.spectrace.platform.starter.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ImpactQueryService {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final ImpactFindingProjectionService impacts;
    public ImpactQueryService(JdbcTemplate jdbc, JsonMapper json, ImpactFindingProjectionService impacts) {
        this.jdbc = jdbc; this.json = json; this.impacts = impacts;
    }

    @Transactional(readOnly = true)
    public Preview preview(PreviewRequest request, Actor actor) {
        if (!actor.organisationId().equals(request.organisationId())) throw ApiException.forbidden();
        try {
            return new Preview(actor.organisationId(), CorrelationId.current().orElseGet(CorrelationId::newId),
                    impacts.preview(actor.organisationId(), request.specificationChange(), request.ruleSetVersionId(), request.jurisdiction()));
        } catch (IllegalStateException unavailable) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PROJECTION_UNAVAILABLE", "Exact released projections or current published Label are unavailable.");
        } catch (IllegalArgumentException invalid) {
            throw ApiException.invalid("The requested specification change does not identify exact related versions.");
        }
    }

    public ImpactFinding get(String id, Actor actor) {
        var rows = jdbc.query("SELECT organisation_id, finding_json FROM impact_finding_projection WHERE finding_id = ?",
                (rs, n) -> new OwnedFinding(rs.getString(1), rs.getString(2)), id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Finding not found.");
        var row = rows.getFirst();
        if (!row.organisation().equals(actor.organisationId())) throw ApiException.forbidden();
        return json.readValue(row.body(), ImpactFinding.class);
    }

    public Page list(Actor actor, ImpactFinding.Kind kind, ImpactFinding.Outcome outcome, int limit, String cursor) {
        if (limit < 1 || limit > 100) throw ApiException.invalid("limit must be between 1 and 100.");
        Cursor after = cursor == null ? null : decode(cursor);
        String kindValue = kind == null ? null : kind.name();
        String outcomeValue = outcome == null ? null : outcome.name();
        if (after != null && !actor.organisationId().equals(after.organisation())) throw ApiException.forbidden();
        if (after != null && (!Objects.equals(kindValue, after.kind()) || !Objects.equals(outcomeValue, after.outcome()))) {
            throw ApiException.invalid("Cursor filters do not match this request.");
        }
        Timestamp time = after == null ? Timestamp.from(Instant.EPOCH) : Timestamp.from(after.createdAt());
        String id = after == null ? "" : after.id();
        var rows = jdbc.query("""
                SELECT finding_json, created_at, finding_id FROM impact_finding_projection
                WHERE organisation_id = ? AND (? IS NULL OR kind = ?) AND (? IS NULL OR outcome = ?)
                AND (created_at > ? OR (created_at = ? AND finding_id > ?))
                ORDER BY created_at, finding_id LIMIT ?
                """, (rs, n) -> new FindingRow(json.readValue(rs.getString(1), ImpactFinding.class),
                rs.getTimestamp(2).toInstant(), rs.getString(3)), actor.organisationId(), kindValue, kindValue,
                outcomeValue, outcomeValue, time, time, id, limit + 1);
        var pageRows = rows.stream().limit(limit).toList();
        String next = null;
        if (rows.size() > limit) {
            var last = pageRows.getLast();
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(
                    new Cursor(actor.organisationId(), last.createdAt(), last.id(), kindValue, outcomeValue)));
        }
        return new Page(actor.organisationId(), pageRows.stream().map(FindingRow::finding).toList(), next);
    }

    private Cursor decode(String cursor) {
        try {
            if (cursor.isBlank() || cursor.length() > 2048) throw new IllegalArgumentException();
            var decoded = json.readValue(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8), Cursor.class);
            if (decoded.createdAt() == null || decoded.id() == null || decoded.id().isBlank()
                    || decoded.organisation() == null || decoded.organisation().isBlank()) throw new IllegalArgumentException();
            return decoded;
        } catch (RuntimeException invalid) {
            throw ApiException.invalid("Invalid cursor.");
        }
    }

    public record PreviewRequest(String organisationId, ImpactFinding.SpecificationChange specificationChange,
                                 String ruleSetVersionId, String jurisdiction) {
        public PreviewRequest {
            if (organisationId == null || organisationId.isBlank() || specificationChange == null
                    || ruleSetVersionId == null || ruleSetVersionId.isBlank() || jurisdiction == null || jurisdiction.isBlank()) {
                throw ApiException.invalid("A complete impact preview request is required.");
            }
        }
    }
    public record Preview(String organisationId, String correlationId, List<ImpactFinding> findings) { }
    public record Page(String organisationId, List<ImpactFinding> items, String nextCursor) { }
    private record Cursor(String organisation, Instant createdAt, String id, String kind, String outcome) { }
    private record FindingRow(ImpactFinding finding, Instant createdAt, String id) { }
    private record OwnedFinding(String organisation, String body) { }
}
