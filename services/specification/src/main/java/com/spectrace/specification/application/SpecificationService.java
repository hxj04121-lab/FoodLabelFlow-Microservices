package com.spectrace.specification.application;

import com.spectrace.platform.starter.error.ApiException;
import com.spectrace.platform.starter.messaging.LocalAudit;
import com.spectrace.platform.starter.messaging.Outbox;
import com.spectrace.specification.persistence.SpecificationStore;
import com.spectrace.specification.persistence.SpecificationStore.MaterialRow;
import com.spectrace.specification.persistence.SpecificationStore.SpecificationRow;
import com.spectrace.specification.security.Caller;
import com.spectrace.specification.security.Permission;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Specification use cases: supplier, material and ingredient reads, draft creation and release.
 *
 * <p>BR-11: only a SUPPLIER organisation that owns the material may create materials, drafts and
 * releases; drafts are visible only to that organisation; released versions are visible to everyone.
 * BR-01: a released version is never changed. BR-10: a release writes the state change, the local audit
 * record and SpecificationPublished.v1 in one transaction.</p>
 */
@Service
@Transactional(readOnly = true)
public class SpecificationService {

    private static final Set<String> STATUSES = Set.of("DRAFT", "RELEASED", "RETIRED");

    private final SpecificationStore store;
    private final Outbox outbox;
    private final LocalAudit audit;
    private final Clock clock;

    public SpecificationService(SpecificationStore store, Outbox outbox, LocalAudit audit, Clock clock) {
        this.store = store;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reads

    public Views.Page<Views.Supplier> suppliers(Integer limit, String cursor) {
        int size = Cursors.limit(limit);
        return Cursors.page(store.suppliers(Cursors.after(cursor), size + 1), size, SpecificationStore.SupplierRow::supplierId,
                SpecificationService::supplierView);
    }

    public Views.Supplier supplier(String supplierId) {
        return store.supplier(supplierId).map(SpecificationService::supplierView)
                .orElseThrow(() -> ApiException.notFound("Supplier " + supplierId + " does not exist."));
    }

    public Views.Page<Views.Material> materials(String supplierId, Integer limit, String cursor) {
        int size = Cursors.limit(limit);
        return Cursors.page(store.materials(supplierId, Cursors.after(cursor), size + 1), size, MaterialRow::materialId,
                SpecificationService::materialView);
    }

    public Views.Material material(String materialId) {
        return store.material(materialId, false).map(SpecificationService::materialView)
                .orElseThrow(() -> ApiException.notFound("Material " + materialId + " does not exist."));
    }

    public Views.Page<Views.Ingredient> ingredients(String prefix, Integer limit, String cursor) {
        if (prefix != null && (prefix.isEmpty() || prefix.length() > 100)) {
            throw ApiException.invalid("q must be 1 to 100 characters");
        }
        int size = Cursors.limit(limit);
        return Cursors.page(store.ingredients(prefix, Cursors.after(cursor), size + 1), size,
                SpecificationStore.IngredientRow::ingredientId,
                row -> new Views.Ingredient(row.ingredientId(), row.canonicalName(), row.ingredientKind()));
    }

    public Views.Page<Views.SpecificationVersion> versions(Caller caller, String materialId, String status, Integer limit,
                                                           String cursor) {
        if (status != null && !STATUSES.contains(status)) {
            throw ApiException.invalid("status must be DRAFT, RELEASED or RETIRED");
        }
        int size = Cursors.limit(limit);
        return Cursors.page(store.versions(materialId, status, caller.organisationId(), Cursors.after(cursor), size + 1),
                size, SpecificationRow::specificationVersionId, this::versionView);
    }

    public Views.SpecificationVersion version(Caller caller, String specificationVersionId) {
        SpecificationRow row = existing(specificationVersionId, false);
        if ("DRAFT".equals(row.lifecycleStatus()) && !caller.belongsTo(row.organisationId())) {
            throw ApiException.forbidden();
        }
        return versionView(row);
    }

    // ---------------------------------------------------------------- writes

    @Transactional
    public Views.Material createMaterial(Caller caller, Commands.CreateMaterial command) {
        SpecificationStore.SupplierRow supplier = store.supplier(command.supplierId()).orElse(null);
        // An unknown supplier is reported like another organisation's supplier: the caller does not own it.
        requireSupplierOwner(caller, Permission.WRITE_SPECIFICATION, supplier == null ? null : supplier.organisationId());
        requireProvenance(command.provenanceId());
        if (store.materialCodeExists(supplier.supplierId(), command.materialCode())) {
            throw ApiException.conflict("Material code " + command.materialCode() + " already exists for this supplier.");
        }
        String materialId = "mat_" + UUID.randomUUID();
        store.insertMaterial(materialId, supplier.supplierId(), command.materialCode(), command.materialName(),
                command.description(), command.provenanceId());
        audit.record("MATERIAL_CREATED", "MATERIAL", materialId, caller.subject(), caller.organisationId(),
                Map.of("supplierId", supplier.supplierId(), "materialCode", command.materialCode()));
        return material(materialId);
    }

    @Transactional
    public Views.SpecificationVersion createDraft(Caller caller, Commands.CreateSpecification command) {
        MaterialRow material = store.material(command.materialId(), true)
                .orElseThrow(() -> ApiException.notFound("Material " + command.materialId() + " does not exist."));
        requireSupplierOwner(caller, Permission.WRITE_SPECIFICATION, material.organisationId());
        requireProvenance(command.provenanceId());
        var ingredients = store.ingredientsById(command.components().stream().map(Commands.ComponentInput::ingredientId).toList());
        for (Commands.ComponentInput component : command.components()) {
            if (!ingredients.containsKey(component.ingredientId())) {
                throw unprocessable("INGREDIENT_UNKNOWN", "Ingredient " + component.ingredientId()
                        + " is not in the vocabulary.");
            }
        }
        requireEffectiveDateNotBeforeReleased(material.materialId(), "", command.effectiveDate());

        int versionNumber = store.latestVersionNumber(material.materialId()) + 1;
        String id = "spec_" + UUID.randomUUID();
        store.insertDraft(id, material.materialId(), versionNumber, command.effectiveDate(), caller.subject(),
                command.provenanceId());
        for (int i = 0; i < command.components().size(); i++) {
            Commands.ComponentInput component = command.components().get(i);
            // Components that point at a PLACEHOLDER vocabulary entry are unresolved for Compliance (BR-03).
            String status = "PLACEHOLDER".equals(ingredients.get(component.ingredientId()).ingredientKind()) ? "UNMAPPED" : "MATCHED";
            store.insertComponent("%s_c%02d".formatted(id, i + 1), id, i + 1, component.ingredientId(),
                    component.rawPhrase(), component.matchRule(), status);
        }
        audit.record("SPECIFICATION_CREATED", "SPECIFICATION_VERSION", id, caller.subject(), caller.organisationId(),
                Map.of("materialId", material.materialId(), "versionNumber", versionNumber));
        return versionView(existing(id, false));
    }

    @Transactional
    public Views.SpecificationVersion release(Caller caller, String specificationVersionId) {
        SpecificationRow found = existing(specificationVersionId, false);
        // Lock order: material, then the version (the same order as draft creation).
        MaterialRow material = store.material(found.materialId(), true).orElseThrow();
        SpecificationRow draft = existing(specificationVersionId, true);
        requireSupplierOwner(caller, Permission.RELEASE_SPECIFICATION, draft.organisationId());
        if (!"DRAFT".equals(draft.lifecycleStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_IMMUTABLE",
                    "Only a draft can be released; create a new version for changes.");
        }
        requireEffectiveDateNotBeforeReleased(material.materialId(), draft.specificationVersionId(), draft.effectiveDate());

        Instant releasedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (!store.release(specificationVersionId, releasedAt)) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_IMMUTABLE", "The version is no longer a draft.");
        }
        SpecificationRow released = existing(specificationVersionId, false);
        audit.record("SPECIFICATION_RELEASED", "SPECIFICATION_VERSION", specificationVersionId, caller.subject(),
                caller.organisationId(), Map.of("materialId", material.materialId(), "versionNumber", released.versionNumber()));
        SpecificationPublished payload = published(released, material);
        outbox.append(SpecificationPublished.EVENT_TYPE, released.organisationId(), material.materialId(),
                released.versionNumber(), payload);
        return versionView(released);
    }

    /** Payload for a released version; also used by the bootstrap replay (STCN-115). */
    public SpecificationPublished published(SpecificationRow released, MaterialRow material) {
        SpecificationStore.SupplierRow supplier = store.supplier(material.supplierId()).orElseThrow();
        var previous = store.previousReleased(material.materialId(), released.versionNumber())
                .map(row -> new SpecificationPublished.VersionReference(row.specificationVersionId(), row.versionNumber()))
                .orElse(null);
        List<SpecificationPublished.ComponentRef> components = store.components(released.specificationVersionId()).stream()
                .map(c -> new SpecificationPublished.ComponentRef(c.specComponentId(), c.sequenceNo(), c.ingredientId(),
                        c.ingredientName(), c.rawPhrase(), c.matchStatus()))
                .toList();
        String sourceType = store.provenanceSourceType(released.provenanceId()).orElseThrow();
        return new SpecificationPublished(supplier.organisationId(),
                new SpecificationPublished.SupplierRef(supplier.supplierId(), supplier.supplierCode(), supplier.supplierName()),
                new SpecificationPublished.MaterialRef(material.materialId(), material.materialCode(), material.materialName()),
                new SpecificationPublished.VersionReference(released.specificationVersionId(), released.versionNumber()),
                previous, released.effectiveDate().toString(), released.releasedAt().toString(), components,
                new SpecificationPublished.Provenance(released.provenanceId(), sourceType));
    }

    // ---------------------------------------------------------------- rules

    private static void requireSupplierOwner(Caller caller, Permission permission, String owningOrganisationId) {
        if (!caller.isSupplier() || !caller.has(permission) || owningOrganisationId == null
                || !caller.belongsTo(owningOrganisationId)) {
            throw ApiException.forbidden();
        }
    }

    private void requireProvenance(String provenanceId) {
        if (store.provenanceSourceType(provenanceId).isEmpty()) {
            throw ApiException.invalid("provenanceId " + provenanceId + " is not a known provenance record");
        }
    }

    /** A new version may not become effective before a version that is already released. */
    private void requireEffectiveDateNotBeforeReleased(String materialId, String excluding, java.time.LocalDate effectiveDate) {
        store.latestReleasedEffectiveDate(materialId, excluding).ifPresent(latest -> {
            if (effectiveDate.isBefore(latest)) {
                throw unprocessable("EFFECTIVE_DATE_INVALID",
                        "effectiveDate must not be earlier than the latest released version (" + latest + ").");
            }
        });
    }

    private SpecificationRow existing(String specificationVersionId, boolean lock) {
        return store.version(specificationVersionId, lock).orElseThrow(() ->
                ApiException.notFound("Specification version " + specificationVersionId + " does not exist."));
    }

    private static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    private static Views.Supplier supplierView(SpecificationStore.SupplierRow row) {
        return new Views.Supplier(row.supplierId(), row.organisationId(), row.supplierCode(), row.supplierName());
    }

    private static Views.Material materialView(MaterialRow row) {
        return new Views.Material(row.materialId(), row.organisationId(), row.supplierId(), row.materialCode(),
                row.materialName(), row.description(), row.provenanceId());
    }

    private Views.SpecificationVersion versionView(SpecificationRow row) {
        return new Views.SpecificationVersion(row.specificationVersionId(), row.organisationId(), row.materialId(),
                row.versionNumber(), row.lifecycleStatus(), row.effectiveDate(), row.releasedAt(), row.createdBySubject(),
                row.provenanceId(), store.components(row.specificationVersionId()));
    }
}
