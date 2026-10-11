package com.spectrace.platform.starter.messaging;

import com.spectrace.platform.starter.correlation.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bootstrap replay for a fresh staging window (STCN-115): on start-up, appends the service's seeded
 * released versions to the outbox in one transaction under a fixed correlation ID. Subclasses derive
 * event IDs from the seed and use {@link Outbox#appendOnce}, so replaying again appends nothing.
 */
public abstract class SeedReplayRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedReplayRunner.class);

    private final TransactionTemplate transactions;
    private final String source;
    private final String eventType;

    /** {@code source} names the service in the correlation ID {@code seed-replay-<source>}. */
    protected SeedReplayRunner(TransactionTemplate transactions, String source, String eventType) {
        this.transactions = transactions;
        this.source = source;
        this.eventType = eventType;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer appended = transactions.execute(status -> replay());
        log.info("Seed replay appended {} {} events", appended, eventType);
    }

    /** Appends every seeded event not yet in the outbox; returns how many were appended. */
    public int replay() {
        try (CorrelationId.Scope ignored = CorrelationId.bind("seed-replay-" + source)) {
            return appendSeeded();
        }
    }

    protected abstract int appendSeeded();
}
