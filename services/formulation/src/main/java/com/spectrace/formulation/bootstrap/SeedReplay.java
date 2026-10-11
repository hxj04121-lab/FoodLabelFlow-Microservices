package com.spectrace.formulation.bootstrap;

import com.spectrace.formulation.application.FormulaPublished;
import com.spectrace.formulation.application.FormulationService;
import com.spectrace.formulation.persistence.FormulationStore;
import com.spectrace.platform.starter.messaging.Outbox;
import com.spectrace.platform.starter.messaging.SeedReplayRunner;
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
public class SeedReplay extends SeedReplayRunner {

    private final FormulationStore store;
    private final FormulationService service;
    private final Outbox outbox;

    public SeedReplay(FormulationStore store, FormulationService service, Outbox outbox, TransactionTemplate transactions) {
        super(transactions, "formulation", FormulaPublished.EVENT_TYPE);
        this.store = store;
        this.service = service;
        this.outbox = outbox;
    }

    @Override
    protected int appendSeeded() {
        int appended = 0;
        for (FormulationStore.FormulaRow formula : store.currentReleased()) {
            FormulationStore.ProductRow product = store.product(formula.productId(), false).orElseThrow();
            FormulaPublished payload = service.published(formula, product, null);
            if (outbox.appendOnce(Outbox.eventIdFor("seed:" + formula.formulaVersionId()), formula.releasedAt(),
                    FormulaPublished.EVENT_TYPE, product.organisationId(), product.productId(), formula.versionNumber(),
                    payload)) {
                appended++;
            }
        }
        return appended;
    }
}
