# PayLedger

A **personal budgeting app with household budgets and group splitting** (India first), built on a production-style
**double-entry ledger**. It is built to show how money systems stay correct under retries, concurrency and partial failure.

> Status: Phase 1 ledger core ✅ · Phase 2 Kafka & outbox ✅ · **Phase 3 foundations in progress** (ledger v2 ✅) · see [Roadmap](#roadmap) and the [product plan](docs/plan/product-plan.md)

## What it guarantees

| Guarantee | How |
|---|---|
| Money is never created or destroyed | Every entry's postings sum to zero **per currency**, and stay inside one book, enforced by a **deferred Postgres constraint trigger** at commit time |
| Cached balances are always right, even under heavy concurrency | `SELECT … FOR UPDATE` row locks, balance updated in the same transaction as the postings |
| No deadlocks between entries touching the same accounts in opposite order | Accounts are created and locked in one consistent order (`ORDER BY`) |
| A retried request is never recorded twice | Required `Idempotency-Key` header + `UNIQUE` constraint + request fingerprint |
| Full audit trail; edits never overwrite | Postings are append-only (UPDATE/DELETE rejected by trigger) and store `balance_after`; an edit is a **reversal** (at most one per entry, `UNIQUE`) plus a new entry |
| Exact arithmetic | Amounts are `BIGINT` minor units; overflow is checked with `Math.addExact` |
| Every committed movement is announced exactly once in effect, and only committed ones | **Transactional outbox** (event row in the same transaction) + at-least-once relay to Kafka + idempotent consumers |
| A Kafka outage never blocks a write | Events queue in `outbox_events` and drain when Kafka is back (`payledger.outbox.pending` metric) |

## Architecture

```
                         ┌──────────────────── ledger-service :8080 ───────────────────┐
Client ──HTTP──▶         │ LedgerController ─▶ LedgerService (books, typed accounts)     │
                         │                                         │ one transaction:   │
                         │                                         ├─ postings + balances ──▶ PostgreSQL "ledger"
                         │                                         └─ outbox_events ─────────▶   (same DB)
                         │ OutboxRelay (poll, SKIP LOCKED, acks=all) ◀── unpublished rows      │
                         └──────────────────────────────┬──────────────────────────────┘
                                                        ▼
                                          Kafka topic ledger.entries.v2 (key = book)
                                                        ▼
                                  consumers: core-service (Phase 3), notifications (Phase 6)
```

Coming in Phase 3: Keycloak, an API gateway, **core-service** (spaces, accounts, transactions, budgets, groups) and the
React app. notification-service is **parked**: it still reads the wallet-era `ledger.entries.v1` events, which nothing
publishes any more, and is repurposed for emails and the activity feed in Phase 6.

Each service has its own database, Dockerfile, CI job and configuration (environment variables with local defaults),
and can be deployed, scaled or stopped on its own. They share only the documented event contract:
[docs/events/ledger-entry-posted.md](docs/events/ledger-entry-posted.md).

| Service | Port (API / actuator) | Owns | Needs |
|---|---|---|---|
| [ledger-service](services/ledger-service) | 8080 / 8081 | books, accounts, journal, outbox, topic `ledger.entries.v2` | Postgres; Kafka optional (`OUTBOX_RELAY_ENABLED=false`) |
| [notification-service](services/notification-service) (parked) | 8090 / 8091 | per-user notification feed, its dead-letter topic | Postgres, Kafka |

## Run it locally

Prerequisites: Java 21 and Docker. Everything below works with no configuration; to change ports or topic settings,
`cp .env.example .env` and edit it.

**Everything in containers** (built from source, like production):

```bash
docker compose --profile app up -d --build   # Postgres x2, Kafka, ledger-service, notification-service
scripts/smoke-test.sh                         # end-to-end check: transfer -> outbox -> Kafka -> notification
docker compose --profile tools up -d          # optional: Kafka UI on http://localhost:8085
```

**Infrastructure in Docker, services from your IDE / terminal** (fastest edit-run loop):

```bash
docker compose up -d                                      # Postgres (5432, 5433) + Kafka (localhost:9092)
cd services/ledger-service && ./mvnw spring-boot:run      # :8080
cd services/notification-service && ./mvnw spring-boot:run   # :8090
```

**Mix and match**: run one service in Docker and the other from the IDE with
`docker compose --profile ledger up -d --build` or `--profile notification`.

**One service alone** (Testcontainers starts its Postgres and Kafka for you):

```bash
cd services/ledger-service && ./mvnw spring-boot:test-run
cd services/notification-service && ./mvnw spring-boot:test-run
```

No Docker at all? The ledger can run against an embedded PostgreSQL 17 (data kept in `target/dev-postgres`); the outbox relay
stays off unless `KAFKA_BOOTSTRAP_SERVERS` is set, so events just queue:

```bash
cd services/ledger-service
./mvnw spring-boot:test-run -Dspring-boot.run.main-class=dev.payledger.ledger.LocalDevApplication
```

Swagger UI: http://localhost:8080/swagger-ui.html and http://localhost:8090/swagger-ui.html.
Health and Prometheus metrics are on the actuator ports (8081, 8091).

Stop with `docker compose --profile app --profile tools down` (add `-v` to delete the data).

### Configuration

| Variable | Default | Service |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD`, `DB_POOL_SIZE` | local Postgres | both |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | both |
| `LEDGER_EVENTS_TOPIC` | `ledger.entries.v2` (ledger), `ledger.entries.v1` (notification) | both |
| `LEDGER_EVENTS_PARTITIONS` | `3` | ledger (topic creation) |
| `OUTBOX_RELAY_ENABLED` | `true` | ledger |
| `OUTBOX_POLL_INTERVAL`, `OUTBOX_BATCH_SIZE`, `OUTBOX_RETENTION` | `500ms`, `100`, `7d` | ledger |
| `KAFKA_CONSUMER_GROUP`, `KAFKA_LISTENER_CONCURRENCY` | `notification-service`, `3` | notification |
| `LEDGER_EVENTS_DLT` | `notification-service.ledger.entries.v1.dlt` | notification |
| `EVENT_RETRY_ATTEMPTS`, `EVENT_RETRY_INTERVAL` | `4`, `1s` | notification |
| `PORT`, `MANAGEMENT_PORT` | 8080/8081, 8090/8091 | both |

## API

The ledger API (`/api/v2`) is internal: core-service calls it, browsers won't. Amounts are signed minor units
(paise), **positive = debit**. Books and accounts are created by the first entry that uses them.

```bash
# salary ₹80,000 into the bank (the book "space:demo" and both accounts are created on the fly)
curl -s -X POST localhost:8080/api/v2/entries -H 'Content-Type: application/json' -H 'Idempotency-Key: salary-oct' \
  -d '{"bookRef":"space:demo","bookKind":"PERSONAL","entryType":"INCOME","effectiveDate":"2026-10-01","legs":[
       {"accountRef":"bank:hdfc","accountType":"ASSET","currency":"INR","amount":8000000},
       {"accountRef":"income:salary","accountType":"INCOME","currency":"INR","amount":-8000000}]}'

# dinner ₹2,500 on the credit card; run it twice: the second response says "Idempotent-Replayed: true"
curl -si -X POST localhost:8080/api/v2/entries -H 'Content-Type: application/json' -H 'Idempotency-Key: dinner-v1' \
  -d '{"bookRef":"space:demo","bookKind":"PERSONAL","entryType":"EXPENSE","effectiveDate":"2026-10-05","legs":[
       {"accountRef":"category:eating-out","accountType":"EXPENSE","currency":"INR","amount":250000},
       {"accountRef":"card:icici","accountType":"LIABILITY","currency":"INR","amount":-250000}]}'

# edit or delete = reverse it (then post the new version with a new key)
curl -s -X POST localhost:8080/api/v2/reversals -H 'Content-Type: application/json' -H 'Idempotency-Key: dinner-v1-rev' \
  -d '{"reversesIdempotencyKey":"dinner-v1","description":"amount corrected"}'

curl -s "localhost:8080/api/v2/books/space:demo/balances"
curl -s "localhost:8080/api/v2/books/space:demo/totals?from=2026-10-01&to=2026-10-31&type=EXPENSE&byMonth=true"
curl -s "localhost:8080/api/v2/books/space:demo/accounts/card:icici/postings?currency=INR&limit=20"
```

Errors are returned as RFC 9457 `application/problem+json` with a stable `code` (e.g. `ref-conflict`, `reversal-not-allowed`).

## Tests

All tests run against real PostgreSQL and Kafka in Docker (Testcontainers): `./mvnw verify` in each service.

`LedgerIntegrationTest` proves:
- 16 threads × 50 random entries on accounts that don't exist yet (so creation races too): the book still sums to zero
  and each cached balance equals the sum of its postings
- 20 concurrent requests with the same idempotency key are recorded exactly once
- A reversal nets out in the original month, happens at most once, and can't itself be reversed
- Monthly totals follow the business date, not the insert time
- An account keeps its type and a book its kind
- The database itself refuses unbalanced entries, postings into another book, and edits to postings
- Wallet data from before the pivot is preserved in the legacy book

`OutboxIntegrationTest` proves a committed entry reaches Kafka keyed by its book, a rejected one leaves no event,
an idempotent replay emits nothing, and the outbox can't be written outside the business transaction.

`NotificationIntegrationTest` proves a redelivered event creates no duplicate notification, system accounts are skipped,
unknown fields are ignored, and poison messages go to the dead-letter topic without blocking the events behind them.

## Production & deployment (ledger only, for now; to be revisited after the pivot)

Hardened multi-arch Docker image, Caddy with automatic HTTPS, private networks, backups and CI/CD to GitHub Container Registry.
See **[docs/deployment.md](docs/deployment.md)** for free hosting on Oracle Cloud Always Free or Render + Neon.

In production every `/api/**` call needs an `X-API-Key` header.
The single-VM production stack doesn't include Kafka yet, so it runs with `OUTBOX_RELAY_ENABLED=false`: events are kept in
`outbox_events` and will be published once a broker is added. Cloud deployment of the full system is planned for later.

## Design decisions

See [`docs/adr`](docs/adr) for the reasoning behind each decision, including the alternatives rejected.
Study guides for each phase are in [`docs/learning`](docs/learning).

## Roadmap

PayLedger is becoming a **personal budgeting app with group splitting** (guests can join by link, no sign-up needed).
The wallet features (deposits, transfers) are retired; the ledger, outbox and Kafka pipeline are its foundation.
Full plan: **[docs/plan/product-plan.md](docs/plan/product-plan.md)**.

| Phase | Scope | Status |
|---|---|---|
| 1. Ledger core | Double-entry schema with DB-enforced invariants, ordered locking, idempotency keys, hardened image, CI | ✅ |
| 2. Events | Transactional outbox + relay, Kafka, notification-service (idempotent consumer, dead-letter topic), local compose stack | ✅ |
| 3. Foundations | Ledger v2 (books, account types, reversals, period totals) ✅ · Keycloak, gateway, core-service, React shell with theming ⏳ | ⏳ |
| 4. Personal budgeting | Accounts, transactions, categories, budgets, dashboard, reports, CSV import | ⏳ |
| 5. Splitting | Groups, invites, join links, guests, split types, settle up, simplify debts | ⏳ |
| 6. Engagement & SaaS | Notifications, budget alerts, recurring, goals, data export, account deletion | ⏳ |
| 7. Operability | Tracing, dashboards, load tests, cloud deployment | ⏳ |
