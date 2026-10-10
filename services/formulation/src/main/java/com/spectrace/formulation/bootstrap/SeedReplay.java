package com.spectrace.formulation.bootstrap;

import com.spectrace.formulation.application.FormulaPublished;
import com.spectrace.formulation.application.FormulationService;
import com.spectrace.formulation.persistence.FormulationStore;
import com.spectrace.platform.starter.correlation.CorrelationId;
import com.spectrace.platform.starter.messaging.Outbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bootstrap replay for a fresh staging window (G2-M1.6, STCN-115): appends one FormulaPublished.v1 per
 * product's current released formula, so Compliance and Label Workflow build their formula projections
 * from events. Event IDs are derived from the seed ({@code seed:<formulaVersionId>}) and {@code occurredAt}
 * is the release time, so a second replay adds nothing and consumers see duplicates.
 *
 * <p>Enabled with {@code spectrace.seed-replay.enabled=true} (set by staging-up, not by default).</p>
 */
@Component
@ConditionalOnProperty(prefix = "spectrace.seed-replay", name = "enabled", havingValue = "true")
public class SeedReplay implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedReplay.class);

    private final FormulationStore store;
    private final FormulationService service;
    private final Outbox outbox;
    private final TransactionTemplate transactions;

    public SeedReplay(FormulationStore store, FormulationService service, Outbox outbox, TransactionTemplate transactions) {
        this.store = store;
        this.service = service;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer appended = transactions.execute(status -> replay());
        log.info("Seed replay appended {} FormulaPublished.v1 events", appended);
    }

    public int replay() {
        int appended = 0;
        try (CorrelationId.Scope ignored = CorrelationId.bind("seed-replay-formulation")) {
            for (FormulationStore.FormulaRow formula : store.currentReleased()) {
                FormulationStore.ProductRow product = store.product(formula.productId(), false).orElseThrow();
                FormulaPublished payload = service.published(formula, product, null);
                if (outbox.appendOnce(Outbox.eventIdFor("seed:" + formula.formulaVersionId()), formula.releasedAt(),
                        FormulaPublished.EVENT_TYPE, product.organisationId(), product.productId(), formula.versionNumber(),
                        payload)) {
                    appended++;
                }
            }
        }
        return appended;
    }
}
