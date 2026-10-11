package dev.payledger.ledger.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param topic      Kafka topic the ledger's events are published to
 * @param partitions partitions to create the topic with (only used if the topic does not exist yet)
 * @param replicas   replication factor to create the topic with
 * @param retention  how long published rows are kept in {@code outbox_events} before being deleted
 */
@ConfigurationProperties("payledger.outbox")
public record OutboxProperties(String topic, int partitions, short replicas, Duration retention, Relay relay) {

    /**
     * @param enabled     false = events still queue in the outbox but nothing is sent (no Kafka needed)
     * @param batchSize   rows locked and sent per transaction
     * @param sendTimeout how long to wait for Kafka to acknowledge a batch before rolling back and retrying
     */
    public record Relay(boolean enabled, int batchSize, Duration sendTimeout) {
    }
}
