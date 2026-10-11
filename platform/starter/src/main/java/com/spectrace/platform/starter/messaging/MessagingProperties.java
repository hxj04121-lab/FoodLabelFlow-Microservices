package com.spectrace.platform.starter.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code spectrace.messaging.*}: outbox relay and consumer settings. */
@ConfigurationProperties("spectrace.messaging")
public class MessagingProperties {

    /** Envelope {@code producer}; defaults to {@code <spring.application.name>-service}. */
    private String producer;

    /** Topic exchange that every event is published to (architecture v3 §6.4). */
    private String exchange = "spectrace.events";

    private final Relay relay = new Relay();

    public String getProducer() {
        return producer;
    }

    public void setProducer(String producer) {
        this.producer = producer;
    }

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public Relay getRelay() {
        return relay;
    }

    public static class Relay {

        /** Whether this instance runs the relay. Every replica may run it: rows are claimed with SKIP LOCKED. */
        private boolean enabled = true;

        /** Delay between relay polls. */
        private Duration pollInterval = Duration.ofMillis(500);

        /** Maximum outbox rows published per poll. */
        private int batchSize = 50;

        /** How long to wait for broker confirms before the batch counts as failed and is retried. */
        private Duration confirmTimeout = Duration.ofSeconds(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getConfirmTimeout() {
            return confirmTimeout;
        }

        public void setConfirmTimeout(Duration confirmTimeout) {
            this.confirmTimeout = confirmTimeout;
        }
    }
}
