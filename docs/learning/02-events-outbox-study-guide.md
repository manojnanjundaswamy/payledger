# Phase 2 study guide: events, Kafka and the outbox

Goal: you can explain how a committed transfer becomes a notification, and what happens at each step when something crashes.

## 1. Read the code in this order
1. `ledger-service/.../db/migration/V2__outbox.sql`: the outbox table and why it has a partial index
2. `journal/LedgerService.java#postInTransaction`: where the event is written, and why it's inside the transaction
3. `outbox/OutboxRelay.java`: lock → send → wait for acks → mark published
4. `notification-service/.../events/LedgerEventListener.java` and `KafkaConfig.java`: parse, retry, dead-letter
5. `notification-service/.../db/migration/V1__notifications.sql`: the unique constraint that makes the consumer idempotent
6. `OutboxIntegrationTest` and `NotificationIntegrationTest`: the proof

## 2. Exercises
1. **Lose an event on purpose.** Move the `outbox.append(...)` call so it runs after `tx.execute(...)` returns, in a
   separate transaction. Kill the app (`docker compose kill ledger-service`) between the two. Explain the gap.
2. **Watch the backlog.** `docker compose stop kafka`, make a few transfers, read `payledger_outbox_pending` on
   `localhost:8081/actuator/prometheus`, then `docker compose start kafka` and watch it drain.
3. **Prove the consumer is idempotent.** In Kafka UI (`--profile tools`, port 8085), reset the `notification-service`
   group's offsets to the earliest offset and restart it. The feed must not change.
4. **Poison message.** Produce `{"schemaVersion":99}` to `ledger.entries.v1` from Kafka UI and find it on the DLT,
   with the exception headers.
5. **Scale out.** `docker compose --profile app up -d --scale notification-service=3` (remove its host `ports` first).
   Look at partition assignment in Kafka UI. Why does a 3-partition topic cap useful consumers at 3?
6. **Add a consumer**: an `analytics-service` that keeps daily volume per currency. It must survive replays.

## 3. Interview questions
**Q: Why not just call `kafkaTemplate.send()` after saving?**
Two systems, no shared transaction: crash after commit loses the event; send before a rollback announces money that never moved. See ADR 0004.

**Q: Exactly-once?**
End to end it's at-least-once delivery with idempotent processing, which together give an exactly-once *effect*. Kafka's
transactions only cover Kafka-to-Kafka; the database write is outside them.

**Q: How do you keep events ordered?**
Kafka orders per partition; the record key picks the partition. Key = entry id. The idempotent producer
(`enable.idempotence=true`) stops retries from reordering. There is no global order across partitions.

**Q: Why `FOR UPDATE SKIP LOCKED` in the relay?**
Several instances can poll concurrently without blocking each other or double-sending a row they are both working on.

**Q: What goes to the dead-letter topic, and what is retried?**
Transient errors (DB down) are retried in place with back-off, which keeps order. Errors that can never succeed (bad JSON,
unknown schema) go straight to the DLT so one bad record doesn't block the partition.

**Q: Polling vs CDC (Debezium)?**
Polling: simple, no extra infrastructure, ~poll-interval latency, adds DB load. CDC: reads the WAL, lower latency, no polling,
but Kafka Connect to operate. Both can share the same outbox table.

## 4. Concepts to study alongside
- *Designing Data-Intensive Applications*, chapter 11 (Stream Processing)
- microservices.io: "Transactional outbox" and "Idempotent consumer" patterns
- Kafka docs: producer `acks`, `enable.idempotence`, consumer groups and rebalancing
