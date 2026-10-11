package dev.payledger.ledger.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Polls {@code outbox_events} and publishes unpublished rows to Kafka.
 * <p>
 * Each batch is one database transaction:
 * <ol>
 *   <li>{@code SELECT ... FOR UPDATE SKIP LOCKED}: several ledger instances can run the relay at once,
 *       each takes different rows, and none blocks the others,</li>
 *   <li>send every row and wait for Kafka to acknowledge all of them ({@code acks=all}),</li>
 *   <li>mark the rows published and commit.</li>
 * </ol>
 * If Kafka is down or slow, the batch rolls back and is retried on the next poll. Money keeps
 * moving meanwhile; events just queue up (see the {@code payledger.outbox.pending} gauge).
 * A crash after step 2 but before step 3 re-sends the batch: delivery is at-least-once, never at-most-once.
 */
@Component
@ConditionalOnBooleanProperty(name = "payledger.outbox.relay.enabled", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final OutboxProperties props;
    private final Counter published;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, PlatformTransactionManager txManager,
                       OutboxProperties props, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = new TransactionTemplate(txManager);
        this.props = props;
        this.published = Counter.builder("payledger.outbox.published")
                .description("Outbox events acknowledged by Kafka")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${payledger.outbox.relay.poll-interval}")
    public void relayPending() {
        try {
            // Keep draining while batches come back full, so a backlog clears without waiting for the next tick.
            Integer sent;
            do {
                sent = tx.execute(status -> publishBatch());
            } while (sent != null && sent == props.relay().batchSize());
        } catch (RuntimeException e) {
            log.warn("Outbox relay could not publish, will retry on the next poll: {}", e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${payledger.outbox.cleanup-interval}")
    public void deletePublished() {
        int deleted = jdbc.sql("""
                        delete from outbox_events
                        where published_at < now() - make_interval(secs => :seconds)
                        """)
                .param("seconds", props.retention().toSeconds())
                .update();
        if (deleted > 0) {
            log.info("Deleted {} published outbox events older than {}", deleted, props.retention());
        }
    }

    private int publishBatch() {
        List<OutboxRow> rows = jdbc.sql("""
                        select id, event_id, aggregate_id, event_type, payload::text as payload
                        from outbox_events
                        where published_at is null
                        order by id
                        limit :limit
                        for update skip locked
                        """)
                .param("limit", props.relay().batchSize())
                .query(OutboxRow.class)
                .list();
        if (rows.isEmpty()) {
            return 0;
        }

        // Send the whole batch before waiting: the idempotent producer keeps per-partition order.
        List<CompletableFuture<?>> acks = rows.stream().<CompletableFuture<?>>map(row -> kafka.send(toRecord(row))).toList();
        awaitAll(acks);

        jdbc.sql("update outbox_events set published_at = now() where id in (:ids)")
                .param("ids", rows.stream().map(OutboxRow::id).toList())
                .update();
        published.increment(rows.size());
        return rows.size();
    }

    private ProducerRecord<String, String> toRecord(OutboxRow row) {
        var record = new ProducerRecord<>(props.topic(), row.aggregateId().toString(), row.payload());
        record.headers()
                .add("eventId", row.eventId().toString().getBytes(StandardCharsets.UTF_8))
                .add("eventType", row.eventType().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private void awaitAll(List<CompletableFuture<?>> acks) {
        try {
            CompletableFuture.allOf(acks.toArray(CompletableFuture[]::new))
                    .get(props.relay().sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for Kafka", e);
        } catch (ExecutionException | TimeoutException e) {
            // Rolls back the transaction: nothing is marked published, the batch is retried.
            throw new IllegalStateException("Kafka did not acknowledge the batch", e);
        }
    }

    record OutboxRow(long id, UUID eventId, UUID aggregateId, String eventType, String payload) {
    }
}
