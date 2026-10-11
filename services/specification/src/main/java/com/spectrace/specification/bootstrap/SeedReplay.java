package com.spectrace.specification.bootstrap;

import com.spectrace.platform.starter.messaging.Outbox;
import com.spectrace.platform.starter.messaging.SeedReplayRunner;
import com.spectrace.specification.application.SpecificationPublished;
import com.spectrace.specification.application.SpecificationService;
import com.spectrace.specification.persistence.SpecificationStore;
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
public class SeedReplay extends SeedReplayRunner {

    private final SpecificationStore store;
    private final SpecificationService service;
    private final Outbox outbox;

    public SeedReplay(SpecificationStore store, SpecificationService service, Outbox outbox, TransactionTemplate transactions) {
        super(transactions, "specification", SpecificationPublished.EVENT_TYPE);
        this.store = store;
        this.service = service;
        this.outbox = outbox;
    }

    @Override
    protected int appendSeeded() {
        int appended = 0;
        for (SpecificationStore.SpecificationRow released : store.allReleased()) {
            SpecificationStore.MaterialRow material = store.material(released.materialId(), false).orElseThrow();
            SpecificationPublished payload = service.published(released, material);
            if (outbox.appendOnce(Outbox.eventIdFor("seed:" + released.specificationVersionId()), released.releasedAt(),
                    SpecificationPublished.EVENT_TYPE, released.organisationId(), released.materialId(),
                    released.versionNumber(), payload)) {
                appended++;
            }
        }
        return appended;
    }
}
