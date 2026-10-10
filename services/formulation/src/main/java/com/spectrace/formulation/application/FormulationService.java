package com.spectrace.formulation.application;

import com.spectrace.formulation.persistence.FormulationStore;
import com.spectrace.formulation.persistence.FormulationStore.FormulaRow;
import com.spectrace.formulation.persistence.FormulationStore.ProductRow;
import com.spectrace.formulation.persistence.FormulationStore.ProjectionRow;
import com.spectrace.formulation.security.Caller;
import com.spectrace.formulation.security.Permission;
import com.spectrace.platform.starter.error.ApiException;
import com.spectrace.platform.starter.messaging.LocalAudit;
import com.spectrace.platform.starter.messaging.Outbox;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Formulation use cases: products, formula drafts, release and trace, and the released-specification
 * projection.
 *
 * <p>BR-11: products and formulas are visible only to the owning MANUFACTURER organisation; another
 * organisation's resource is 403 without content. BR-01/BR-02: a release makes the draft the single
 * current released formula; earlier versions keep their content and release metadata. BR-10: the release,
 * its audit record and FormulaPublished.v1 commit together. Every item must pin a released, effective
 * specification of the same material known to the projection (architecture v3 section 7.2).</p>
 */
@Service
@Transactional(readOnly = true)
public class FormulationService {

    private final FormulationStore store;
    private final Outbox outbox;
    private final LocalAudit audit;
    private final Clock clock;

    public FormulationService(FormulationStore store, Outbox outbox, LocalAudit audit, Clock clock) {
        this.store = store;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reads

    public Views.Page<Views.Product> products(Caller caller, Integer limit, String cursor) {
        requireManufacturer(caller);
        int size = Cursors.limit(limit);
        return Cursors.page(store.products(caller.organisationId(), Cursors.after(cursor), size + 1), size,
                ProductRow::productId, this::productView);
    }

    public Views.Product product(Caller caller, String productId) {
        return productView(ownedProduct(caller, productId, false));
    }

    public Views.Page<Views.FormulaVersion> formulas(Caller caller, String productId, Integer limit, String cursor) {
        ProductRow product = ownedProduct(caller, productId, false);
        int size = Cursors.limit(limit);
        int below = cursor == null ? Integer.MAX_VALUE : parseVersionCursor(Cursors.after(cursor));
        return Cursors.page(store.formulas(product.productId(), below, size + 1), size,
                row -> Integer.toString(row.versionNumber()), this::formulaView);
    }

    public Views.FormulaVersion formula(Caller caller, String formulaVersionId) {
        return formulaView(ownedFormula(caller, formulaVersionId, false));
    }

    public Views.FormulaTrace trace(Caller caller, String formulaVersionId) {
        FormulaRow formula = ownedFormula(caller, formulaVersionId, false);
        List<Views.TraceItem> items = store.items(formulaVersionId).stream().map(item -> {
            ProjectionRow spec = store.releasedSpecification(item.specificationVersion().id()).orElseThrow(() ->
                    new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SPECIFICATION_PROJECTION_UNAVAILABLE",
                            "The released specification projection does not yet contain a referenced version."));
            return new Views.TraceItem(item.formulaItemId(), item.sequenceNo(), item.materialId(), item.specificationVersion(),
                    spec.supplierId(), spec.supplierOrganisationId(), spec.effectiveDate());
        }).toList();
        return new Views.FormulaTrace(new Views.VersionReference(formula.formulaVersionId(), formula.versionNumber()),
                formula.productId(), items);
    }

    public Views.Page<Views.ReleasedSpecification> releasedSpecifications(String materialId, Integer limit, String cursor) {
        int size = Cursors.limit(limit);
        return Cursors.page(store.releasedSpecifications(materialId, Cursors.after(cursor), size + 1), size,
                ProjectionRow::specificationVersionId,
                row -> new Views.ReleasedSpecification(new Views.VersionReference(row.specificationVersionId(), row.versionNumber()),
                        row.materialId(), row.supplierId(), row.supplierOrganisationId(), row.effectiveDate(), row.receivedAt()));
    }

    // ---------------------------------------------------------------- writes

    @Transactional
    public Views.FormulaVersion createDraft(Caller caller, Commands.CreateFormula command) {
        ProductRow product = ownedProduct(caller, command.productId(), true);
        requirePermission(caller, Permission.WRITE_FORMULA);
        if (store.provenanceSourceType(command.provenanceId()).isEmpty()) {
            throw ApiException.invalid("provenanceId " + command.provenanceId() + " is not a known provenance record");
        }
        // Validate (and share-lock) every referenced specification in a stable order before writing.
        List<ProjectionRow> specs = command.items().stream()
                .sorted(Comparator.comparing(Commands.FormulaItemInput::specificationVersionId))
                .map(item -> eligible(item.materialId(), item.specificationVersionId())).toList();
        Map<String, Integer> versionNumbers = specs.stream()
                .collect(java.util.stream.Collectors.toMap(ProjectionRow::specificationVersionId, ProjectionRow::versionNumber,
                        (a, b) -> a));

        int versionNumber = store.latestVersionNumber(product.productId()) + 1;
        String id = "formula_" + UUID.randomUUID();
        store.insertDraft(id, product.productId(), versionNumber, caller.subject(), command.provenanceId());
        for (int i = 0; i < command.items().size(); i++) {
            Commands.FormulaItemInput item = command.items().get(i);
            store.insertItem("%s_i%02d".formatted(id, i + 1), id, i + 1, item.materialId(), item.specificationVersionId(),
                    versionNumbers.get(item.specificationVersionId()), item.quantity(), item.unit());
        }
        audit.record("FORMULA_CREATED", "FORMULA_VERSION", id, caller.subject(), caller.organisationId(),
                Map.of("productId", product.productId(), "versionNumber", versionNumber));
        return formulaView(store.formula(id, false).orElseThrow());
    }

    @Transactional
    public Views.FormulaVersion release(Caller caller, String formulaVersionId, Commands.ReleaseFormula command) {
        FormulaRow found = ownedFormula(caller, formulaVersionId, false);
        // Lock order: product (the current-pointer concurrency token), then the formula (as in the baseline).
        ProductRow product = store.product(found.productId(), true).orElseThrow();
        FormulaRow draft = store.formula(formulaVersionId, true).orElseThrow();
        requirePermission(caller, Permission.RELEASE_FORMULA);
        if (!"DRAFT".equals(draft.lifecycleStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_IMMUTABLE",
                    "Only a draft can be released; create a new version for changes.");
        }
        if (!Objects.equals(command.expectedCurrentFormulaVersionId(), product.currentFormulaVersionId())
                || store.latestReleasedVersionNumber(product.productId()) > draft.versionNumber()) {
            throw new ApiException(HttpStatus.CONFLICT, "CURRENT_FORMULA_CHANGED",
                    "The product's current formula changed; refresh the product and release a new draft.");
        }
        List<Views.FormulaItem> items = store.items(formulaVersionId);
        items.stream().sorted(Comparator.comparing(item -> item.specificationVersion().id()))
                .forEach(item -> eligible(item.materialId(), item.specificationVersion().id()));

        FormulaRow previous = product.currentFormulaVersionId() == null ? null
                : store.formula(product.currentFormulaVersionId(), false).orElseThrow();
        Instant releasedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        store.clearCurrent(product.productId());
        if (!store.release(formulaVersionId, caller.subject(), releasedAt)) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_IMMUTABLE", "The version is no longer a draft.");
        }
        store.pointProductAt(product.productId(), formulaVersionId);
        FormulaRow released = store.formula(formulaVersionId, false).orElseThrow();
        audit.record("FORMULA_RELEASED", "FORMULA_VERSION", formulaVersionId, caller.subject(), caller.organisationId(),
                Map.of("productId", product.productId(), "versionNumber", released.versionNumber(),
                        "previousFormulaVersionId", previous == null ? "" : previous.formulaVersionId()));
        outbox.append(FormulaPublished.EVENT_TYPE, product.organisationId(), product.productId(), released.versionNumber(),
                published(released, product, previous == null ? null
                        : new Views.VersionReference(previous.formulaVersionId(), previous.versionNumber())));
        return formulaView(released);
    }

    /** Payload for a released formula; also used by the bootstrap replay (STCN-115). */
    public FormulaPublished published(FormulaRow released, ProductRow product, Views.VersionReference previous) {
        List<FormulaPublished.Item> items = store.items(released.formulaVersionId()).stream()
                .map(item -> new FormulaPublished.Item(item.formulaItemId(), item.sequenceNo(), item.materialId(),
                        item.specificationVersion(), item.quantity(), item.unit()))
                .toList();
        return new FormulaPublished(product.organisationId(),
                new FormulaPublished.ProductRef(product.productId(), product.productDescription()),
                new Views.VersionReference(released.formulaVersionId(), released.versionNumber()), previous,
                released.releasedAt().toString(), items,
                new FormulaPublished.Provenance(released.provenanceId(),
                        store.provenanceSourceType(released.provenanceId()).orElseThrow()));
    }

    // ---------------------------------------------------------------- rules

    private ProjectionRow eligible(String materialId, String specificationVersionId) {
        ProjectionRow spec = store.releasedSpecification(specificationVersionId).orElseThrow(() -> unprocessable(
                "SPECIFICATION_NOT_RELEASED", "Specification " + specificationVersionId
                        + " is not a released specification known to Formulation."));
        if (!spec.materialId().equals(materialId)) {
            throw unprocessable("SPECIFICATION_MATERIAL_MISMATCH", "Specification " + specificationVersionId
                    + " belongs to a different material.");
        }
        if (spec.effectiveDate().isAfter(LocalDate.now(clock.withZone(ZoneOffset.UTC)))) {
            throw unprocessable("SPECIFICATION_NOT_EFFECTIVE", "Specification " + specificationVersionId
                    + " is not effective until " + spec.effectiveDate() + ".");
        }
        return spec;
    }

    private ProductRow ownedProduct(Caller caller, String productId, boolean lock) {
        requireManufacturer(caller);
        ProductRow product = store.product(productId, lock)
                .orElseThrow(() -> ApiException.notFound("Product " + productId + " does not exist."));
        if (!caller.belongsTo(product.organisationId())) {
            throw ApiException.forbidden();
        }
        return product;
    }

    private FormulaRow ownedFormula(Caller caller, String formulaVersionId, boolean lock) {
        requireManufacturer(caller);
        FormulaRow formula = store.formula(formulaVersionId, lock)
                .orElseThrow(() -> ApiException.notFound("Formula version " + formulaVersionId + " does not exist."));
        if (!caller.belongsTo(formula.organisationId())) {
            throw ApiException.forbidden();
        }
        return formula;
    }

    private static void requireManufacturer(Caller caller) {
        if (!caller.isManufacturer()) {
            throw ApiException.forbidden();
        }
    }

    private static void requirePermission(Caller caller, Permission permission) {
        if (!caller.has(permission)) {
            throw ApiException.forbidden();
        }
    }

    private static int parseVersionCursor(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw ApiException.invalid("cursor is not a cursor returned by this API");
        }
    }

    private static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    private Views.Product productView(ProductRow row) {
        Views.VersionReference current = row.currentFormulaVersionId() == null ? null
                : store.formula(row.currentFormulaVersionId(), false)
                .map(f -> new Views.VersionReference(f.formulaVersionId(), f.versionNumber())).orElse(null);
        return new Views.Product(row.productId(), row.organisationId(), row.productDescription(), row.brandOwner(),
                row.normalizedCategory(), current);
    }

    private Views.FormulaVersion formulaView(FormulaRow row) {
        return new Views.FormulaVersion(row.formulaVersionId(), row.organisationId(), row.productId(), row.versionNumber(),
                row.lifecycleStatus(), row.currentReleased(), row.createdBySubject(), row.releasedBySubject(),
                row.releasedAt(), row.provenanceId(), store.items(row.formulaVersionId()));
    }
}
