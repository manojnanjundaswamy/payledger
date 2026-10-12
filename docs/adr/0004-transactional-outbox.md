# ADR 0004: Publish ledger events through a transactional outbox

**Status:** Accepted

## Context
Other services (notifications now, analytics and fraud checks later) need to react when money moves.
The ledger commits to Postgres; the events go to Kafka. Two systems cannot share one transaction, so a naive
"commit, then `kafkaTemplate.send()`" has two failure windows:
- commit succeeds, the process crashes before the send: money moved and **nobody is told**;
- send succeeds, the commit then fails (or the send happens inside the transaction and the transaction rolls back):
  consumers are told about **money that never moved**.

## Options considered
| Option | Verdict |
|---|---|
| Send to Kafka after commit | Loses events on a crash or when Kafka is down. |
| Kafka transactions / XA two-phase commit | Postgres and Kafka don't share a coordinator; XA is slow, operationally fragile and poorly supported. |
| Change data capture on `journal_entries` (Debezium) | Correct, but exposes the internal table layout as the public contract and adds Kafka Connect to run. A good later step: Debezium can tail `outbox_events` instead of the polling relay. |
| **Transactional outbox + polling relay** | Event row commits atomically with the postings; a relay publishes it afterwards. Chosen. |

## Decision
- `LedgerService` inserts a `LedgerEntryPosted` event into `outbox_events` in the **same transaction** as the journal entry.
  `OutboxWriter` uses `Propagation.MANDATORY`, so calling it outside a transaction fails.
- `OutboxRelay` polls every 500 ms. It takes up to 100 unpublished rows (`FOR UPDATE SKIP LOCKED`, so several ledger instances
  can relay in parallel), sends them, waits for `acks=all`, then sets `published_at` in the same transaction.
- Record key = the aggregate id (since ledger v2: the **book**), so one book's events stay ordered within one partition. The idempotent producer keeps order across retries.
- The payload is the documented contract ([docs/events/ledger-entry-posted.md](../events/ledger-entry-posted.md)), not the table layout.
- Delivery is **at-least-once**: a crash between Kafka's ack and the `published_at` commit re-sends the batch.
  Consumers de-duplicate on `eventId` (notification-service: `UNIQUE (event_id, account_id)`).
- `OUTBOX_RELAY_ENABLED=false` runs the ledger with no Kafka at all: events wait in the table until the relay runs.

## Consequences
- ➕ Kafka being down never blocks or fails a payment; the backlog shows up as `payledger.outbox.pending` and
  `payledger.outbox.oldest.pending.age` and drains on recovery (tested by stopping the Kafka container).
- ➕ Consumers can be added, removed or replayed without touching the ledger.
- ➖ Latency: an event reaches Kafka up to one poll interval after the commit.
- ➖ Ordering is per partition, not global. Events of *different* entries touching the same account may be consumed out of
  order across partitions; consumers that care use `balanceAfter` and `occurredAt` rather than arrival order.
- ➖ With several relays running, two batches can interleave on one partition. If strict per-key order matters, run one relay
  (leader election) or move to CDC.
- ➖ The outbox table needs cleanup: published rows are deleted after `OUTBOX_RETENTION` (default 7 days).
