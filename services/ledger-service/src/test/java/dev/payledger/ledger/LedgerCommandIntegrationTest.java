package dev.payledger.ledger;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** core-service's commands (docs/events/ledger-commands.md) arriving over real Kafka. */
@Import({TestcontainersConfiguration.class, LedgerCommandIntegrationTest.Topics.class})
@SpringBootTest
class LedgerCommandIntegrationTest {

    static final String TOPIC = "ledger.commands.v1";
    static final String DLT = "ledger.commands.v1.dlt";

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {
        /** In the real stack kafka-init and core-service create it; here it must exist before the listener starts. */
        @Bean
        NewTopic commandTopic() {
            return new NewTopic(TOPIC, 3, (short) 1);
        }
    }

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ConsumerFactory<String, String> consumerFactory;
    @Autowired JdbcClient jdbc;

    @Test
    void anExpenseCommandIsPostedOnceEvenWhenDeliveredTwice() throws Exception {
        String space = UUID.randomUUID().toString();
        String key = "txn:" + UUID.randomUUID() + ":v1";
        String command = post(space, key, 48_600);

        send(space, "ledger.post", command);
        send(space, "ledger.post", command);

        await().atMost(Duration.ofSeconds(20)).until(() -> entries(key) == 1);
        Thread.sleep(500); // give a (wrong) second posting the chance to show up
        assertThat(entries(key)).isEqualTo(1);
        assertThat(balance(space, "category:groceries")).isEqualTo(48_600);
        assertThat(balance(space, "account:card")).isEqualTo(-48_600);
    }

    @Test
    void anEditIsAReversalFollowedByTheNewVersion() {
        String space = UUID.randomUUID().toString();
        String txn = "txn:" + UUID.randomUUID();

        send(space, "ledger.post", post(space, txn + ":v1", 250_000));
        send(space, "ledger.reverse", """
                {"commandId":"%s","commandType":"ledger.reverse","schemaVersion":1,"idempotencyKey":"%s:v1:reversal",
                 "bookRef":"space:%s","reversesIdempotencyKey":"%s:v1","description":"Edited: Dinner"}
                """.formatted(UUID.randomUUID(), txn, space, txn));
        send(space, "ledger.post", post(space, txn + ":v2", 180_000));

        await().atMost(Duration.ofSeconds(20)).until(() -> entries(txn + ":v2") == 1);
        assertThat(entries(txn + ":v1:reversal")).isEqualTo(1);
        assertThat(balance(space, "category:groceries")).isEqualTo(180_000);
        assertThat(balance(space, "account:card")).isEqualTo(-180_000);
    }

    @Test
    void commandsThatCanNeverSucceedAreDeadLetteredWithoutBlockingTheOnesBehindThem() {
        String space = UUID.randomUUID().toString();
        String good = "txn:" + UUID.randomUUID() + ":v1";
        String unbalanced = post(space, "txn:" + UUID.randomUUID() + ":v1", 100).replace("\"amount\":-100", "\"amount\":-99");

        send(space, "ledger.post", "{not json");
        send(space, "ledger.post", unbalanced);
        send(space, "ledger.teleport", post(space, "txn:" + UUID.randomUUID() + ":v1", 100));
        send(space, "ledger.post", post(space, good, 700));

        await().atMost(Duration.ofSeconds(30)).until(() -> entries(good) == 1);
        assertThat(deadLettered(space, 3)).hasSize(3);
    }

    // ---------------------------------------------------------------- helpers

    private static String post(String space, String key, long amount) {
        return """
                {"commandId":"%s","commandType":"ledger.post","schemaVersion":1,"idempotencyKey":"%s",
                 "bookRef":"space:%s","bookKind":"PERSONAL","entryType":"EXPENSE","effectiveDate":"2026-10-05",
                 "description":"Dinner","sourceRef":"txn:x","legs":[
                   {"accountRef":"category:groceries","accountType":"EXPENSE","currency":"INR","amount":%d},
                   {"accountRef":"account:card","accountType":"LIABILITY","currency":"INR","amount":-%d}]}
                """.formatted(UUID.randomUUID(), key, space, amount, amount);
    }

    private void send(String space, String type, String payload) {
        var record = new ProducerRecord<>(TOPIC, space, payload);
        record.headers().add("eventType", type.getBytes(StandardCharsets.UTF_8));
        kafka.send(record).join();
    }

    private long entries(String idempotencyKey) {
        return jdbc.sql("select count(*) from journal_entries where idempotency_key = ?").param(idempotencyKey).query(Long.class).single();
    }

    private long balance(String space, String accountRef) {
        return jdbc.sql("""
                        select coalesce(sum(p.amount), 0) from postings p
                        join accounts a on a.id = p.account_id join books b on b.id = a.book_id
                        where b.ref = ? and a.ref = ?
                        """)
                .param("space:" + space).param(accountRef).query(Long.class).single();
    }

    private List<String> deadLettered(String space, int expected) {
        var overrides = new Properties();
        overrides.put("auto.offset.reset", "earliest");
        List<String> found = new ArrayList<>();
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("dlt-test-" + UUID.randomUUID(), null, null, overrides)) {
            await().atMost(Duration.ofSeconds(20)).until(() -> !consumer.partitionsFor(DLT).isEmpty());
            List<TopicPartition> partitions = consumer.partitionsFor(DLT).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (found.size() < expected && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                    if (space.equals(record.key())) {
                        found.add(record.value());
                    }
                }
            }
        }
        return found;
    }
}
