package dev.payledger.ledger;

import dev.payledger.ledger.account.Account;
import dev.payledger.ledger.account.AccountService;
import dev.payledger.ledger.common.InsufficientFundsException;
import dev.payledger.ledger.journal.LedgerEntryPosted;
import dev.payledger.ledger.journal.PostingOutcome;
import dev.payledger.ledger.movement.MoneyMovementService;
import dev.payledger.ledger.outbox.OutboxProperties;
import dev.payledger.ledger.outbox.OutboxWriter;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.transaction.IllegalTransactionStateException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OutboxIntegrationTest {

    @Autowired AccountService accountService;
    @Autowired MoneyMovementService movements;
    @Autowired OutboxWriter outboxWriter;
    @Autowired OutboxProperties outboxProperties;
    @Autowired ConsumerFactory<String, String> consumerFactory;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    @Test
    void committedMovementIsPublishedToKafkaWithTheOwnerOfEveryLeg() {
        Account alice = accountService.open("alice", "USD");
        Account bob = accountService.open("bob", "USD");
        movements.deposit(key(), alice.getId(), 10_000, "USD", null);

        PostingOutcome transfer = movements.transfer(key(), alice.getId(), bob.getId(), 2_500, "USD", "dinner");
        UUID entryId = transfer.entry().id();

        ConsumerRecord<String, String> record = awaitRecord(entryId.toString());
        assertThat(header(record, "eventType")).isEqualTo(LedgerEntryPosted.TYPE);

        LedgerEntryPosted event = json.readValue(record.value(), LedgerEntryPosted.class);
        assertThat(event.eventId().toString()).isEqualTo(header(record, "eventId"));
        assertThat(event.entryId()).isEqualTo(entryId);
        assertThat(event.description()).isEqualTo("dinner");
        assertThat(event.postings())
                .extracting(LedgerEntryPosted.Leg::ownerId, LedgerEntryPosted.Leg::amount, LedgerEntryPosted.Leg::balanceAfter)
                .containsExactlyInAnyOrder(
                        tuple("alice", -2_500L, 7_500L),
                        tuple("bob", 2_500L, 2_500L));

        assertThat(jdbc.sql("select published_at from outbox_events where aggregate_id = ?")
                .param(entryId).query(Instant.class).single()).isNotNull();
    }

    @Test
    void rolledBackMovementLeavesNoEvent() {
        Account alice = accountService.open("alice", "USD");
        Account bob = accountService.open("bob", "USD");
        long before = outboxRows();

        assertThatThrownBy(() -> movements.transfer(key(), alice.getId(), bob.getId(), 1, "USD", null))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(outboxRows()).isEqualTo(before);
    }

    @Test
    void idempotentReplayDoesNotEmitASecondEvent() {
        Account alice = accountService.open("alice", "USD");
        String key = key();

        PostingOutcome first = movements.deposit(key, alice.getId(), 500, "USD", null);
        movements.deposit(key, alice.getId(), 500, "USD", null);

        assertThat(jdbc.sql("select count(*) from outbox_events where aggregate_id = ?")
                .param(first.entry().id()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void outboxCannotBeWrittenOutsideTheBusinessTransaction() {
        assertThatThrownBy(() -> outboxWriter.append("Test", UUID.randomUUID(), UUID.randomUUID(), "test", "{}"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private ConsumerRecord<String, String> awaitRecord(String key) {
        var overrides = new Properties();
        overrides.put("auto.offset.reset", "earliest");
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("outbox-test-" + UUID.randomUUID(), null, null, overrides)) {
            List<TopicPartition> partitions = consumer.partitionsFor(outboxProperties.topic()).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);

            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        return fail("no Kafka record with key %s within 20s", key);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private long outboxRows() {
        return jdbc.sql("select count(*) from outbox_events").query(Long.class).single();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
