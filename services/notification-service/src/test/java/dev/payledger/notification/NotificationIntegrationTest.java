package dev.payledger.notification;

import dev.payledger.notification.events.EventProperties;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "payledger.events.retry.interval=50ms")
@AutoConfigureMockMvc
class NotificationIntegrationTest {

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ConsumerFactory<String, String> consumerFactory;
    @Autowired EventProperties props;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;

    @Test
    void transferNotifiesBothWalletsAndADuplicateDeliveryIsIgnored() throws Exception {
        String alice = owner("alice");
        String bob = owner("bob");
        UUID entryId = UUID.randomUUID();
        String transfer = transferEvent(UUID.randomUUID(), entryId, alice, bob, 2_500);

        send(entryId, transfer);
        send(entryId, transfer); // redelivery: same eventId
        // Same key = same partition = processed in order, so once the marker is in, the duplicate was handled too.
        UUID marker = UUID.randomUUID();
        send(entryId, transferEvent(UUID.randomUUID(), marker, alice, bob, 1));
        await(() -> countForEntry(marker) == 2);

        assertThat(countForEntry(entryId)).isEqualTo(2);
        mvc.perform(get("/api/v1/notifications").param("ownerId", alice).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].message").value("You sent 0.01 USD to " + bob + ". Balance: 99.99 USD."))
                .andExpect(jsonPath("$.nextCursor").isNumber());
        mvc.perform(get("/api/v1/notifications").param("ownerId", bob))
                .andExpect(jsonPath("$.items[1].message").value("You received 25.00 USD from " + alice + ". Balance: 25.00 USD."))
                .andExpect(jsonPath("$.items[1].amount").value(2_500));
    }

    @Test
    void depositDoesNotNotifyTheInternalFundingAccount() {
        String alice = owner("alice");
        UUID entryId = UUID.randomUUID();
        send(entryId, """
                {"eventId":"%s","eventType":"ledger.entry.posted","schemaVersion":1,"occurredAt":"2026-01-01T00:00:00Z",
                 "entryId":"%s","entryType":"DEPOSIT","currency":"USD","description":null,"someFutureField":42,
                 "postings":[
                   {"accountId":"%s","ownerId":"system:external-funding","accountType":"SYSTEM","amount":-10000,"balanceAfter":-10000},
                   {"accountId":"%s","ownerId":"%s","accountType":"USER","amount":10000,"balanceAfter":10000}]}
                """.formatted(UUID.randomUUID(), entryId, UUID.randomUUID(), UUID.randomUUID(), alice));

        await(() -> countForEntry(entryId) == 1);
        assertThat(jdbc.sql("select message from notifications where entry_id = ?").param(entryId)
                .query(String.class).single()).isEqualTo("Deposit of 100.00 USD received. Balance: 100.00 USD.");
    }

    @Test
    void poisonMessagesAreDeadLetteredAndDoNotBlockThePartition() {
        UUID key = UUID.randomUUID();
        send(key, "this is not json");
        send(key, """
                {"eventId":"%s","eventType":"ledger.entry.posted","schemaVersion":99,"entryId":"%s","postings":[]}
                """.formatted(UUID.randomUUID(), key));
        UUID next = UUID.randomUUID();
        send(key, transferEvent(UUID.randomUUID(), next, owner("a"), owner("b"), 100));

        await(() -> countForEntry(next) == 2);
        List<ConsumerRecord<String, String>> dead = deadLetters(key.toString(), 2);
        assertThat(dead).extracting(ConsumerRecord::value).first().isEqualTo("this is not json");
        assertThat(header(dead.get(1), "kafka_dlt-exception-message")).contains("unsupported schemaVersion 99");
    }

    private void send(UUID key, String payload) {
        var record = new ProducerRecord<>(props.ledgerTopic(), key.toString(), payload);
        record.headers().add("eventType", "ledger.entry.posted".getBytes(StandardCharsets.UTF_8));
        kafka.send(record).join();
    }

    private static String transferEvent(UUID eventId, UUID entryId, String from, String to, long amount) {
        return """
                {"eventId":"%s","eventType":"ledger.entry.posted","schemaVersion":1,"occurredAt":"2026-01-01T00:00:00Z",
                 "entryId":"%s","entryType":"TRANSFER","currency":"USD","description":"dinner",
                 "postings":[
                   {"accountId":"%s","ownerId":"%s","accountType":"USER","amount":%d,"balanceAfter":%d},
                   {"accountId":"%s","ownerId":"%s","accountType":"USER","amount":%d,"balanceAfter":%d}]}
                """.formatted(eventId, entryId,
                uuidFor(from), from, -amount, 10_000 - amount,
                uuidFor(to), to, amount, amount);
    }

    private static UUID uuidFor(String owner) {
        return UUID.nameUUIDFromBytes(owner.getBytes(StandardCharsets.UTF_8));
    }

    private long countForEntry(UUID entryId) {
        return jdbc.sql("select count(*) from notifications where entry_id = ?").param(entryId).query(Long.class).single();
    }

    private List<ConsumerRecord<String, String>> deadLetters(String key, int expected) {
        var overrides = new Properties();
        overrides.put("auto.offset.reset", "earliest");
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("dlt-test-" + UUID.randomUUID(), null, null, overrides)) {
            List<TopicPartition> partitions = consumer.partitionsFor(props.deadLetterTopic()).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            var found = new ArrayList<ConsumerRecord<String, String>>();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline && found.size() < expected) {
                consumer.poll(Duration.ofMillis(250)).forEach(r -> {
                    if (key.equals(r.key())) found.add(r);
                });
            }
            return found;
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        fail("condition not met within 20s");
    }

    /** Unique per test run, so tests don't see each other's notifications. */
    private static String owner(String name) {
        return name + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
