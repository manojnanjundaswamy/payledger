# PayLedger

A production-style **payments and wallet platform** built on a **double-entry ledger**.
It is built to show how money systems stay correct under retries, concurrency and partial failure.

> Status: **Phase 1 – Ledger core** ✅ · **Phase 2 – Kafka & outbox** ✅ · Next: budgeting + splitting app, see [Roadmap](#roadmap)

## What it guarantees

| Guarantee | How |
|---|---|
| Money is never created or destroyed | Every journal entry's postings sum to zero, enforced by a **deferred Postgres constraint trigger** at commit time |
| No overdrafts, even under heavy concurrency | `SELECT … FOR UPDATE` row locks taken in a consistent order + a `CHECK (balance >= 0)` constraint |
| No deadlocks between A→B and B→A transfers | All transactions lock accounts through one query with `ORDER BY id` |
| A retried request never pays twice | Required `Idempotency-Key` header + `UNIQUE` constraint + request fingerprint |
| Full audit trail | Postings are append-only (UPDATE/DELETE rejected by trigger) and store `balance_after` |
| Exact arithmetic | Amounts are `BIGINT` minor units; overflow is checked with `Math.addExact` |
| Every committed movement is announced exactly once in effect, and only committed ones | **Transactional outbox** (event row in the same transaction) + at-least-once relay to Kafka + idempotent consumers |
| A Kafka outage never blocks a payment | Events queue in `outbox_events` and drain when Kafka is back (`payledger.outbox.pending` metric) |

## Architecture

```
                         ┌──────────────────── ledger-service :8080 ───────────────────┐
Client ──HTTP──▶         │ Controllers ─▶ MoneyMovementService ─▶ LedgerService         │
                         │                                         │ one transaction:   │
                         │                                         ├─ postings + balances ──▶ PostgreSQL "ledger"
                         │                                         └─ outbox_events ─────────▶   (same DB)
                         │ OutboxRelay (poll, SKIP LOCKED, acks=all) ◀── unpublished rows      │
                         └──────────────────────────────┬──────────────────────────────┘
                                                        ▼
                                          Kafka topic ledger.entries.v1 (key = entry id)
                                                        ▼
                         ┌──────────────── notification-service :8090 ─────────────────┐
Client ──HTTP──▶         │ LedgerEventListener ─▶ NotificationService ─▶ PostgreSQL "notifications"
                         │   retry ▸ dead-letter topic          UNIQUE(event_id, account_id) = idempotent
                         └──────────────────────────────────────────────────────────────┘
```

Each service has its own database, Dockerfile, CI job and configuration (environment variables with local defaults),
and can be deployed, scaled or stopped on its own. They share only the documented event contract:
[docs/events/ledger-entry-posted.md](docs/events/ledger-entry-posted.md).

| Service | Port (API / actuator) | Owns | Needs |
|---|---|---|---|
| [ledger-service](services/ledger-service) | 8080 / 8081 | accounts, journal, outbox, topic `ledger.entries.v1` | Postgres; Kafka optional (`OUTBOX_RELAY_ENABLED=false`) |
| [notification-service](services/notification-service) | 8090 / 8091 | per-user notification feed, its dead-letter topic | Postgres, Kafka |

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
| `LEDGER_EVENTS_TOPIC` | `ledger.entries.v1` | both |
| `LEDGER_EVENTS_PARTITIONS` | `3` | ledger (topic creation) |
| `OUTBOX_RELAY_ENABLED` | `true` | ledger |
| `OUTBOX_POLL_INTERVAL`, `OUTBOX_BATCH_SIZE`, `OUTBOX_RETENTION` | `500ms`, `100`, `7d` | ledger |
| `KAFKA_CONSUMER_GROUP`, `KAFKA_LISTENER_CONCURRENCY` | `notification-service`, `3` | notification |
| `LEDGER_EVENTS_DLT` | `notification-service.ledger.entries.v1.dlt` | notification |
| `EVENT_RETRY_ATTEMPTS`, `EVENT_RETRY_INTERVAL` | `4`, `1s` | notification |
| `PORT`, `MANAGEMENT_PORT` | 8080/8081, 8090/8091 | both |

## API

```bash
# open two wallets
curl -s -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' -d '{"ownerId":"alice","currency":"USD"}'
curl -s -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' -d '{"ownerId":"bob","currency":"USD"}'

# deposit $100.00 (amounts are in minor units)
curl -s -X POST localhost:8080/api/v1/deposits -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: dep-001' -d '{"accountId":"<alice>","amount":10000,"currency":"USD"}'

# transfer $25.00, then run the same command again: the response says "Idempotent-Replayed: true" and no money moves
curl -si -X POST localhost:8080/api/v1/transfers -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: tr-001' -d '{"fromAccountId":"<alice>","toAccountId":"<bob>","amount":2500,"currency":"USD"}'

# statement (keyset pagination: pass nextCursor as beforeId)
curl -s "localhost:8080/api/v1/accounts/<alice>/statement?limit=20"

# notification feed, built asynchronously from the ledger's events (same pagination)
curl -s "localhost:8090/api/v1/notifications?ownerId=bob"
```

Errors are returned as RFC 9457 `application/problem+json` with a stable `code` (e.g. `insufficient-funds`).

## Tests

All tests run against real PostgreSQL and Kafka in Docker (Testcontainers): `./mvnw verify` in each service.

`LedgerIntegrationTest` proves:
- 16 threads × 50 random transfers: total money is conserved, no balance goes negative, and each cached balance equals the sum of its postings
- 20 concurrent requests with the same idempotency key move money exactly once
- The database itself refuses unbalanced entries and edits to postings

`OutboxIntegrationTest` proves a committed movement reaches Kafka with every leg's owner, a rolled-back one leaves no event,
an idempotent replay emits nothing, and the outbox can't be written outside the business transaction.

`NotificationIntegrationTest` proves a redelivered event creates no duplicate notification, system accounts are skipped,
unknown fields are ignored, and poison messages go to the dead-letter topic without blocking the events behind them.

## Production & deployment (ledger only, for now)

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
The wallet features (deposits, transfers) will be retired; the ledger, outbox and Kafka pipeline become its foundation.
Full plan: **[docs/plan/product-plan.md](docs/plan/product-plan.md)**.

| Phase | Scope | Status |
|---|---|---|
| 1. Ledger core | Double-entry schema with DB-enforced invariants, ordered locking, idempotency keys, hardened image, CI | ✅ |
| 2. Events | Transactional outbox + relay, Kafka, notification-service (idempotent consumer, dead-letter topic), local compose stack | ✅ |
| 3. Foundations | Ledger redesign (books, account types, reversals), Keycloak, gateway, core-service, React shell with theming | ⏳ |
| 4. Personal budgeting | Accounts, transactions, categories, budgets, dashboard, reports, CSV import | ⏳ |
| 5. Splitting | Groups, invites, join links, guests, split types, settle up, simplify debts | ⏳ |
| 6. Engagement & SaaS | Notifications, budget alerts, recurring, goals, data export, account deletion | ⏳ |
| 7. Operability | Tracing, dashboards, load tests, cloud deployment | ⏳ |
