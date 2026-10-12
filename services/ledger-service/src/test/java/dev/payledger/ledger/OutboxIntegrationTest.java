package dev.payledger.ledger;

import dev.payledger.ledger.account.AccountType;
import dev.payledger.ledger.book.BookKind;
import dev.payledger.ledger.common.RefConflictException;
import dev.payledger.ledger.journal.LedgerEntryPosted;
import dev.payledger.ledger.journal.LedgerService;
import dev.payledger.ledger.journal.PostingCommand;
import dev.payledger.ledger.journal.PostingCommand.Leg;
import dev.payledger.ledger.journal.PostingOutcome;
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
import java.time.LocalDate;
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

    @Autowired LedgerService ledger;
    @Autowired OutboxWriter outboxWriter;
    @Autowired OutboxProperties outboxProperties;
    @Autowired ConsumerFactory<String, String> consumerFactory;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    @Test
    void committedEntryIsPublishedToKafkaKeyedByBook() {
        String book = "group:" + UUID.randomUUID();
        PostingOutcome split = ledger.post(new PostingCommand(key(), book, BookKind.GROUP, "SPLIT",
                LocalDate.of(2026, 10, 10), "dinner", "expense:42", List.of(
                        new Leg("member:alice", AccountType.ASSET, "INR", 2_000_00),
                        new Leg("member:bob", AccountType.LIABILITY, "INR", -1_000_00),
                        new Leg("member:carol", AccountType.LIABILITY, "INR", -1_000_00))));
        UUID entryId = split.entry().id();
        UUID bookId = jdbc.sql("select id from books where ref = ?").param(book).query(UUID.class).single();

        ConsumerRecord<String, String> record = awaitRecord(bookId.toString());
        assertThat(header(record, "eventType")).isEqualTo(LedgerEntryPosted.TYPE);

        LedgerEntryPosted event = json.readValue(record.value(), LedgerEntryPosted.class);
        assertThat(event.eventId().toString()).isEqualTo(header(record, "eventId"));
        assertThat(event.schemaVersion()).isEqualTo(2);
        assertThat(event.entryId()).isEqualTo(entryId);
        assertThat(event.bookRef()).isEqualTo(book);
        assertThat(event.bookKind()).isEqualTo(BookKind.GROUP);
        assertThat(event.sourceRef()).isEqualTo("expense:42");
        assertThat(event.postings())
                .extracting(LedgerEntryPosted.Leg::accountRef, LedgerEntryPosted.Leg::amount)
                .containsExactlyInAnyOrder(tuple("member:alice", 2_000_00L), tuple("member:bob", -1_000_00L),
                        tuple("member:carol", -1_000_00L));

        assertThat(jdbc.sql("select published_at from outbox_events where event_id = ?")
                .param(event.eventId()).query(Instant.class).single()).isNotNull();
    }

    @Test
    void rejectedEntryLeavesNoEvent() {
        String book = "space:" + UUID.randomUUID();
        ledger.post(expense(key(), book, 100));
        long before = outboxRows();

        // "category:x" was created as EXPENSE; posting it as INCOME fails inside the transaction.
        assertThatThrownBy(() -> ledger.post(new PostingCommand(key(), book, BookKind.PERSONAL, "INCOME",
                LocalDate.of(2026, 10, 10), null, null, List.of(
                        new Leg("bank:a", AccountType.ASSET, "INR", 100),
                        new Leg("category:x", AccountType.INCOME, "INR", -100)))))
                .isInstanceOf(RefConflictException.class);

        assertThat(outboxRows()).isEqualTo(before);
    }

    @Test
    void idempotentReplayDoesNotEmitASecondEvent() {
        String book = "space:" + UUID.randomUUID();
        String key = key();

        PostingOutcome first = ledger.post(expense(key, book, 500));
        ledger.post(expense(key, book, 500));

        assertThat(jdbc.sql("select count(*) from outbox_events where payload ->> 'entryId' = ?")
                .param(first.entry().id().toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void outboxCannotBeWrittenOutsideTheBusinessTransaction() {
        assertThatThrownBy(() -> outboxWriter.append("Test", UUID.randomUUID(), UUID.randomUUID(), "test", "{}"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private static PostingCommand expense(String key, String book, long amount) {
        return new PostingCommand(key, book, BookKind.PERSONAL, "EXPENSE", LocalDate.of(2026, 10, 10), null, null,
                List.of(new Leg("category:x", AccountType.EXPENSE, "INR", amount),
                        new Leg("bank:a", AccountType.ASSET, "INR", -amount)));
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
