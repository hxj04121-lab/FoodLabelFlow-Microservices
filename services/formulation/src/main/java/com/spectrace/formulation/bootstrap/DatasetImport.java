package com.spectrace.formulation.bootstrap;

import com.spectrace.formulation.application.FormulaPublished;
import com.spectrace.formulation.application.FormulationService;
import com.spectrace.formulation.application.Views;
import com.spectrace.formulation.persistence.FormulationStore;
import com.spectrace.formulation.persistence.FormulationStore.FormulaRow;
import com.spectrace.formulation.persistence.FormulationStore.ProductRow;
import com.spectrace.formulation.persistence.FormulationStore.ProjectionRow;
import com.spectrace.platform.starter.correlation.CorrelationId;
import com.spectrace.platform.starter.messaging.Outbox;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owner import of the synthetic G3 load dataset (G3-M1.1, STCN-89).
 *
 * <p>Reads {@code formula-owner-import.jsonl} (one FormulaPublished.v1 envelope per line, from
 * {@code perf/g3/experiment.py}) and stores each product and released formula in this service's own
 * database with the dataset's IDs. Every referenced specification version must first arrive in the
 * released-spec projection through SpecificationPublished events (the Specification import), so the import
 * waits for them instead of trusting the file. The FormulaPublished event is built from this service's rows
 * and appended to the outbox with an event ID derived from the formula ID ({@code dataset:<id>}); importing
 * again appends nothing.</p>
 *
 * <p>Enabled by setting {@code spectrace.dataset-import.file}; never on by default.</p>
 */
@Component
@ConditionalOnProperty(prefix = "spectrace.dataset-import", name = "file")
public class DatasetImport implements ApplicationRunner {

    static final String SUBJECT = "dataset-import";
    static final String FIXTURE_GROUP = "G3_LOAD_DATASET";
    private static final Logger log = LoggerFactory.getLogger(DatasetImport.class);

    private final FormulationStore store;
    private final FormulationService service;
    private final Outbox outbox;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final Path file;
    private final Duration projectionTimeout;

    public DatasetImport(FormulationStore store, FormulationService service, Outbox outbox,
                         TransactionTemplate transactions, JsonMapper json,
                         @Value("${spectrace.dataset-import.file}") Path file,
                         @Value("${spectrace.dataset-import.projection-timeout:PT10M}") Duration projectionTimeout) {
        this.store = store;
        this.service = service;
        this.outbox = outbox;
        this.transactions = transactions;
        this.json = json;
        this.file = file;
        this.projectionTimeout = projectionTimeout;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Dataset import appended {} FormulaPublished.v1 events from {}", importFile(file, projectionTimeout), file);
    }

    /** Waits for the referenced specification versions, then imports each formula in its own transaction. */
    public int importFile(Path path, Duration timeout) {
        List<JsonNode> envelopes = read(path);
        envelopes.sort(Comparator.comparing((JsonNode e) -> e.path("aggregateId").asString())
                .thenComparingInt(e -> e.path("payload").path("formulaVersion").path("versionNumber").asInt()));
        awaitProjection(envelopes, timeout);
        int appended = 0;
        try (CorrelationId.Scope ignored = CorrelationId.bind("dataset-import-formulation")) {
            for (JsonNode envelope : envelopes) {
                if (Boolean.TRUE.equals(transactions.execute(status -> importOne(envelope)))) {
                    appended++;
                }
            }
        }
        return appended;
    }

    private void awaitProjection(List<JsonNode> envelopes, Duration timeout) {
        Set<String> missing = new TreeSet<>();
        for (JsonNode envelope : envelopes) {
            for (JsonNode item : envelope.path("payload").path("items")) {
                missing.add(text(item.path("specificationVersion"), "id"));
            }
        }
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            missing.removeIf(id -> store.releasedSpecification(id).isPresent());
            if (missing.isEmpty()) {
                return;
            }
            require(Instant.now().isBefore(deadline), missing.size() + " specification versions never reached the "
                    + "projection, e.g. " + missing.iterator().next() + "; import the Specification dataset first");
            log.info("Dataset import waiting for {} specification versions to arrive by event", missing.size());
            try {
                Thread.sleep(1000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Dataset import interrupted", exception);
            }
        }
    }

    private boolean importOne(JsonNode envelope) {
        require(FormulaPublished.EVENT_TYPE.equals(envelope.path("eventType").asString()),
                "not a " + FormulaPublished.EVENT_TYPE + " envelope");
        JsonNode payload = envelope.path("payload");
        String organisationId = text(payload, "organisationId");
        String provenanceId = text(payload.path("provenance"), "provenanceId");
        require(store.provenanceSourceType(provenanceId).isPresent(), "unknown provenance " + provenanceId);

        JsonNode productNode = payload.path("product");
        String productId = text(productNode, "productId");
        if (store.product(productId, true).isEmpty()) {
            store.insertProduct(productId, organisationId, organisationId, text(productNode, "productDescription"),
                    "Synthetic load", FIXTURE_GROUP, provenanceId);
        }
        ProductRow product = store.product(productId, true).orElseThrow();
        require(product.organisationId().equals(organisationId), "product " + productId + " belongs to another organisation");

        JsonNode version = payload.path("formulaVersion");
        String formulaId = text(version, "id");
        int versionNumber = version.path("versionNumber").asInt();
        FormulaRow formula = store.formula(formulaId, true).orElse(null);
        FormulaRow previous = null;
        if (formula == null) {
            store.insertDraft(formulaId, productId, versionNumber, SUBJECT, provenanceId);
            for (JsonNode item : payload.path("items")) {
                ProjectionRow spec = store.releasedSpecification(text(item.path("specificationVersion"), "id")).orElseThrow();
                require(spec.materialId().equals(text(item, "materialId"))
                        && spec.versionNumber() == item.path("specificationVersion").path("versionNumber").asInt(),
                        "item " + text(item, "formulaItemId") + " does not match the released specification");
                JsonNode quantity = item.path("quantity");
                store.insertItem(text(item, "formulaItemId"), formulaId, item.path("sequenceNo").asInt(), spec.materialId(),
                        spec.specificationVersionId(), spec.versionNumber(),
                        quantity.isNumber() ? quantity.decimalValue() : null,
                        quantity.isNumber() ? text(item, "unit") : null);
            }
            if (product.currentFormulaVersionId() != null) {
                previous = store.formula(product.currentFormulaVersionId(), false).orElseThrow();
            }
            store.clearCurrent(productId);
            require(store.release(formulaId, SUBJECT, Instant.parse(text(payload, "releasedAt"))),
                    "formula " + formulaId + " was not a draft");
            store.pointProductAt(productId, formulaId);
            formula = store.formula(formulaId, false).orElseThrow();
        }
        require(formula.productId().equals(productId) && formula.versionNumber() == versionNumber
                && !"DRAFT".equals(formula.lifecycleStatus()), "formula " + formulaId + " differs from the dataset");
        return outbox.appendOnce(Outbox.eventIdFor("dataset:" + formulaId), formula.releasedAt(),
                FormulaPublished.EVENT_TYPE, organisationId, productId, versionNumber,
                service.published(formula, product, previous == null ? null
                        : new Views.VersionReference(previous.formulaVersionId(), previous.versionNumber())));
    }

    private List<JsonNode> read(Path path) {
        try (var lines = Files.lines(path)) {
            return new ArrayList<>(lines.filter(line -> !line.isBlank()).map(json::readTree).toList());
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot read dataset " + path, exception);
        }
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asString(null);
        require(value != null && !value.isBlank(), "dataset field " + field + " is missing");
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Dataset import: " + message);
        }
    }
}
