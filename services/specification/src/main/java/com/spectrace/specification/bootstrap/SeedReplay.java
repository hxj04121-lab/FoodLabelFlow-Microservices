package com.spectrace.specification.bootstrap;

import com.spectrace.platform.starter.correlation.CorrelationId;
import com.spectrace.platform.starter.messaging.Outbox;
import com.spectrace.specification.application.SpecificationPublished;
import com.spectrace.specification.application.SpecificationService;
import com.spectrace.specification.persistence.SpecificationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bootstrap replay for a fresh staging window (G2-M1.6, STCN-115): appends one SpecificationPublished.v1
 * per seeded released version, in version order, so Formulation and Compliance build their projections
 * from events instead of reading this database. Event IDs and {@code occurredAt} are derived from the
 * seed ({@code seed:<specificationVersionId>}, the release time), so replaying again adds nothing and
 * consumers that already applied an event treat it as a duplicate.
 *
 * <p>Enabled with {@code spectrace.seed-replay.enabled=true} (set by staging-up, not by default).</p>
 */
@Component
@ConditionalOnProperty(prefix = "spectrace.seed-replay", name = "enabled", havingValue = "true")
public class SeedReplay implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedReplay.class);

    private final SpecificationStore store;
    private final SpecificationService service;
    private final Outbox outbox;
    private final TransactionTemplate transactions;

    public SeedReplay(SpecificationStore store, SpecificationService service, Outbox outbox, TransactionTemplate transactions) {
        this.store = store;
        this.service = service;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer appended = transactions.execute(status -> replay());
        log.info("Seed replay appended {} SpecificationPublished.v1 events", appended);
    }

    public int replay() {
        int appended = 0;
        try (CorrelationId.Scope ignored = CorrelationId.bind("seed-replay-specification")) {
            for (SpecificationStore.SpecificationRow released : store.allReleased()) {
                SpecificationStore.MaterialRow material = store.material(released.materialId(), false).orElseThrow();
                SpecificationPublished payload = service.published(released, material);
                if (outbox.appendOnce(Outbox.eventIdFor("seed:" + released.specificationVersionId()), released.releasedAt(),
                        SpecificationPublished.EVENT_TYPE, released.organisationId(), released.materialId(),
                        released.versionNumber(), payload)) {
                    appended++;
                }
            }
        }
        return appended;
    }
}
