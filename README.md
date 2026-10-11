# PayLedger

A production-style **payments and wallet platform** built on a **double-entry ledger**.
It is built to show how money systems stay correct under retries, concurrency and partial failure.

> Status: **Phase 1 – Ledger core** ✅ · Phase 2 – Kafka & outbox ⏳ · Phase 3 – Redis, gateway, React dashboard ⏳ · Phase 4 – Observability, load tests, deploy ⏳

## What it guarantees

| Guarantee | How |
|---|---|
| Money is never created or destroyed | Every journal entry's postings sum to zero, enforced by a **deferred Postgres constraint trigger** at commit time |
| No overdrafts, even under heavy concurrency | `SELECT … FOR UPDATE` row locks taken in a consistent order + a `CHECK (balance >= 0)` constraint |
| No deadlocks between A→B and B→A transfers | All transactions lock accounts through one query with `ORDER BY id` |
| A retried request never pays twice | Required `Idempotency-Key` header + `UNIQUE` constraint + request fingerprint |
| Full audit trail | Postings are append-only (UPDATE/DELETE rejected by trigger) and store `balance_after` |
| Exact arithmetic | Amounts are `BIGINT` minor units; overflow is checked with `Math.addExact` |

## Architecture (Phase 1)

```
Client ──HTTP──▶ ledger-service (Spring Boot 4, Java 21, virtual threads)
                   ├─ AccountController ─▶ AccountService
                   └─ MoneyMovementController ─▶ MoneyMovementService ─▶ LedgerService ─▶ PostgreSQL 17
                                                  (deposit/transfer → postings)   (locks, idempotency,   (constraints,
                                                                                    balanced entries)      triggers)
```

## Run it

Prerequisites: Java 21 and Docker.

```bash
docker compose --profile app up --build   # Postgres + the app, built from source like production
```

Or run Postgres in Docker and the app from your IDE / terminal:

```bash
docker compose up -d
cd services/ledger-service
./mvnw spring-boot:run
```

Then open **http://localhost:8080/swagger-ui.html**. Actuator (health, Prometheus metrics) is on port **8081**.

Or run without Compose (Testcontainers starts Postgres for you):

```bash
./mvnw spring-boot:test-run
```

No Docker at all? Run against an embedded PostgreSQL 17 process (data kept in `target/dev-postgres`):

```bash
./mvnw spring-boot:test-run -Dspring-boot.run.main-class=dev.payledger.ledger.LocalDevApplication
```

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
```

Errors are returned as RFC 9457 `application/problem+json` with a stable `code` (e.g. `insufficient-funds`).

## Tests

`LedgerIntegrationTest` runs against a real PostgreSQL in Docker (Testcontainers) and proves:
- 16 threads × 50 random transfers: total money is conserved, no balance goes negative, and each cached balance equals the sum of its postings
- 20 concurrent requests with the same idempotency key move money exactly once
- The database itself refuses unbalanced entries and edits to postings

## Production & deployment

Hardened multi-arch Docker image, Caddy with automatic HTTPS, private networks, backups and CI/CD to GitHub Container Registry.
See **[docs/deployment.md](docs/deployment.md)** for free hosting on Oracle Cloud Always Free or Render + Neon.

In production every `/api/**` call needs an `X-API-Key` header.

## Design decisions

See [`docs/adr`](docs/adr) for the reasoning behind each decision, including the alternatives rejected.
