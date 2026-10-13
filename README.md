# PayLedger

A **personal budgeting app with household budgets and group splitting** (India first), built on a production-style
**double-entry ledger**. It is built to show how money systems stay correct under retries, concurrency and partial failure.

> Status: Phase 1 ledger core ✅ · Phase 2 Kafka & outbox ✅ · **Phase 3 foundations in progress** (ledger v2 ✅, sign-up/login ✅, web app ✅) · see [Roadmap](#roadmap) and the [product plan](docs/plan/product-plan.md)

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
Browser ──cookie──▶ gateway :8000 ──token──▶ core-service :8060 ──▶ PostgreSQL "core"
   │                 │ login (PKCE), sessions in Redis, CSRF              (users, spaces, accounts, categories,
   │                 ▼                                                     transactions, outbox)
   └──── login ──▶ Keycloak :8180 (sign-up, email verification ▶ Mailpit :8025)

core-service ──outbox──▶ Kafka ledger.commands.v1 (key = space) ──▶ ledger-service :8080 (internal, no browser route)
```

The browser never holds a token: the gateway keeps them in a Redis session and relays the user's access token to
core-service ([ADR 0006](docs/adr/0006-identity-keycloak-and-bff-gateway.md)). The ledger and its event pipeline:

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

In progress (Phase 4): core-service records accounts and transactions and queues the matching ledger commands in its
own outbox; ledger-service consumes them ([docs/events/ledger-commands.md](docs/events/ledger-commands.md)), and
core-service reads balances and per-category totals back from the ledger. The web app has Accounts, Transactions,
Categories, Budgets, Plan (commitments, safe to spend, annual planner), Import and Reports (including the April–March
financial year) screens on real data, and the dashboard widgets use it once you have an account.
notification-service is **parked**: it still reads the wallet-era `ledger.entries.v1` events, which nothing
publishes any more, and is repurposed for emails and the activity feed in Phase 6.

Each service has its own database, Dockerfile, CI job and configuration (environment variables with local defaults),
and can be deployed, scaled or stopped on its own. They share only the documented event contract:
[docs/events/ledger-entry-posted.md](docs/events/ledger-entry-posted.md).

| Service | Port (API / actuator) | Owns | Needs |
|---|---|---|---|
| [web](web) | 5173 | the React app: dashboards, accounts, transactions, theme builder, settings | gateway |
| [gateway](services/gateway) | 8000 / 8001 | login, sessions, CSRF, routing | Keycloak, Redis |
| [core-service](services/core-service) | 8060 / 8061 | users, spaces, money accounts, categories, transactions, topic `ledger.commands.v1` | Postgres, Keycloak (keys); Kafka optional (`OUTBOX_RELAY_ENABLED=false`) |
| [ledger-service](services/ledger-service) | 8080 / 8081 | books, accounts, journal, outbox, topic `ledger.entries.v2`; consumes `ledger.commands.v1` | Postgres; Kafka optional (`OUTBOX_RELAY_ENABLED=false`) |
| [notification-service](services/notification-service) (parked) | 8090 / 8091 | per-user notification feed, its dead-letter topic | Postgres, Kafka |

## Run it locally

Prerequisites: Java 21 and Docker. Everything below works with no configuration; to change ports or topic settings,
`cp .env.example .env` and edit it.

**Everything in containers** (built from source, like production):

```bash
docker compose --profile app up -d --build   # infrastructure + gateway, core-service, ledger-service, notification-service
scripts/login-smoke-test.sh                   # sign-in, sign-up + email verification, token relay, CSRF, logout
scripts/smoke-test.sh                         # ledger: entries, reversal, totals, outbox -> Kafka
scripts/money-smoke-test.sh                   # money: account + expense via gateway -> core -> Kafka -> ledger, edit, delete, balances
docker compose --profile tools up -d          # optional: Kafka UI on http://localhost:8085
```

| What | Where |
|---|---|
| **The app** | **http://localhost:5173** |
| Demo login | `demo@payledger.local` / `demo-password` |
| Emails (verification, password reset) | Mailpit, http://localhost:8025 |
| Keycloak admin console | http://localhost:8180 (`admin` / `admin`) |
| Gateway (the web app talks to this) | http://localhost:8000, e.g. `/api/session` |

**Web app with hot reload** (`docker compose --profile gateway --profile core up -d` first):

```bash
cd web && npm install && npm run dev          # http://localhost:5173, proxies /api and login to the gateway
npm test                                      # unit tests (Vitest)
npm run e2e                                   # browser tests against the running stack (Playwright)
```

**Infrastructure in Docker, services from your IDE / terminal** (fastest edit-run loop):

```bash
docker compose up -d                                      # Postgres, Kafka, Keycloak, Redis, Mailpit
cd services/gateway && ./mvnw spring-boot:run             # :8000
cd services/core-service && ./mvnw spring-boot:run        # :8060
cd services/ledger-service && ./mvnw spring-boot:run      # :8080
cd services/notification-service && ./mvnw spring-boot:run   # :8090
```

**Mix and match**: run one service in Docker and the other from the IDE with
`docker compose --profile <gateway|core|ledger|notification> up -d --build`.

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
| `DB_URL`, `DB_USER`, `DB_PASSWORD`, `DB_POOL_SIZE` | local Postgres | all but gateway |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | ledger, notification |
| `KEYCLOAK_PUBLIC_URL`, `KEYCLOAK_INTERNAL_URL`, `KEYCLOAK_CLIENT_SECRET` | `http://localhost:8180`, same, local secret | gateway |
| `REDIS_HOST`, `CORE_SERVICE_URL`, `FRONTEND_URL`, `COOKIE_SECURE` | `localhost`, `http://localhost:8060`, `http://localhost:5173`, `false` | gateway |
| `KEYCLOAK_ISSUER`, `KEYCLOAK_JWKS_URI` | `http://localhost:8180/realms/payledger`, its certs URL | core |
| `LEDGER_EVENTS_TOPIC` | `ledger.entries.v2` (ledger), `ledger.entries.v1` (notification) | both |
| `LEDGER_EVENTS_PARTITIONS` | `3` | ledger (topic creation) |
| `OUTBOX_RELAY_ENABLED` | `true` | ledger |
| `OUTBOX_POLL_INTERVAL`, `OUTBOX_BATCH_SIZE`, `OUTBOX_RETENTION` | `500ms`, `100`, `7d` | ledger |
| `KAFKA_CONSUMER_GROUP`, `KAFKA_LISTENER_CONCURRENCY` | `notification-service`, `3` | notification |
| `LEDGER_EVENTS_DLT` | `notification-service.ledger.entries.v1.dlt` | notification |
| `EVENT_RETRY_ATTEMPTS`, `EVENT_RETRY_INTERVAL` | `4`, `1s` | notification |
| `PORT`, `MANAGEMENT_PORT` | 8000/8001, 8060/8061, 8080/8081, 8090/8091 | each service |

## API

The app's API is core-service's, under `/api/core` (through the gateway, with the session cookie and CSRF header;
OpenAPI at http://localhost:8060/swagger-ui.html):

| | |
|---|---|
| `GET/PATCH /me` | profile, settings, UI preferences, your spaces |
| `GET/POST /spaces/{id}/accounts`, `GET/PATCH …/accounts/{accountId}` | bank, cash, card, wallet; opening balance; archive; `balance` from the ledger (owed, for a card; null if the ledger is unreachable) |
| `GET /spaces/{id}/category-totals?kind=EXPENSE&from=…&to=…&byMonth=` | spending (or income) per category, positive amounts; 503 `ledger-unavailable` if the ledger is down |
| `GET /spaces/{id}/categories` | every space starts with a default set; hidden ones are included (flagged) |
| `POST /spaces/{id}/categories` | `{kind, name, icon, color, parentId?}`; subcategories go one level deep; 409 `category-name-in-use` (unique per kind, ignoring case) |
| `PUT /spaces/{id}/categories/{cid}` | the whole category (`name, icon, color, parentId, hidden`); hiding a parent hides its subcategories |
| `GET /spaces/{id}/budgets?month=2026-10` | one budget month (default: the current one; named by the month it starts in, on the user's start day): per category limit, spent (a parent includes its subcategories; null if the ledger is down), remaining, status `ON_TRACK`/`CLOSE` (80%)/`OVER`, and the projected spend from day 5 |
| `PUT /spaces/{id}/budgets/{cid}` | `{amount, rollover?, from?}`: the monthly limit from that month on; earlier months keep theirs. With `rollover`, what isn't spent carries into the next month and an overspend comes off it (each budget reports `carried` and `available`; the month reports `income`) |
| `DELETE /spaces/{id}/budgets/{cid}?from=2026-10` | stop budgeting it from that month on |
| `GET/POST /spaces/{id}/commitments`, `PUT …/{cid}?version=`, `DELETE …/{cid}` | bills (`BILL`: an expense in a category) and savings (`SAVING`: a transfer to your own account) due every 1, 2, 3, 4, 6 or 12 months from `firstDue`, optionally until `endsOn` |
| `GET /spaces/{id}/commitments/suggestions` | expenses that look like monthly bills (same description, account and category in 3+ of the last 6 months, steady amount and day, still going), not yet on the plan |
| `GET /spaces/{id}/commitments/due?from=&to=` | every due date in the range (at most two years), with `paid` and the paying `transactionId` |
| `POST /spaces/{id}/commitments/{cid}/payments` | `{dueDate, amount?, occurredOn?}`: records the expense or transfer once (409 `already-paid`); deleting that transaction makes the date due again |
| `GET /spaces/{id}/safe-to-spend` | this budget month: income − spent − bills still due − savings due − set aside for big costs, and per day for the days left (amounts null if the ledger is down) |
| `POST /spaces/{id}/imports/preview` | `{fileName, csv or xlsx (base64), accounts?, categories?}`: checks every row (READY, DUPLICATE or ERROR with column and reason) and lists the account and category names the space doesn't have; saves nothing |
| `POST /spaces/{id}/imports` | imports the READY rows; `accounts` / `categories` map unknown names to an id, or to `new:BANK` (CASH, CARD, WALLET) / `new` to create them. At most 2,000 rows; importing the same file again adds nothing |
| `GET /spaces/{id}/imports`, `DELETE …/{importId}` | past imports; undo deletes every transaction an import added (409 `import-undone` the second time) |
| `GET /spaces/{id}/exports/transactions.csv` or `.xlsx` `?from=&to=` | transactions in the template's columns; each row's `external_id` is the transaction's reference, so importing an export back adds nothing |
| `GET /spaces/{id}/imports/template.xlsx`, `…/template.csv` | the template: the Excel one has dropdowns of your accounts and categories and an instructions tab; both have your account names in their example rows |
| `GET /spaces/{id}/net-worth?months=12` | net worth at the end of each month (this month: today) over open accounts in the space's currency: today's balances less what moved later; 503 if the ledger is down |
| `GET/POST /groups`, `GET/PATCH /groups/{gid}` | your groups for splitting; a group and its members (404 unless you are one); rename, archive (owner) |
| `POST /groups/{gid}/members`, `DELETE …/members/{mid}` | add someone by name and maybe email (an email with an account joins at once, otherwise a placeholder claimed at sign-up; 409 `member-exists`); leave, or remove (owner) |
| `GET /groups/{gid}/link`, `POST …/link/reset`, `GET/POST /groups/join/{token}` | the join link (reset revokes the old one); preview and join by link (takes over a placeholder with your email; 404 `invalid-link`) |
| `GET /plan`, `POST /plan/trial` | your plan (Free or Premium, from a trial, payment or complimentary), Free's limits with your usage; the 30-day trial once (409 `trial-used`). Past a Free limit, adding answers 403 `plan-limit` (docs/plan/market-and-pricing.md) |
| `GET /spaces/{id}/planner` | the annual planner: bills every 6+ months or only once (`endsOn = firstDue`), each with its next date, months left and this month's set-aside (amount ÷ months left) |
| `POST /spaces/{id}/categories/{cid}/merge` | `{into}`: moves every transaction (reversed and re-posted, so past reports move too) and bill to another category of the same kind, drops its budget and hides it |
| `DELETE /spaces/{id}/categories/{cid}` | only if no transaction ever used it: 409 `category-in-use` (hide it instead) or `category-has-subcategories` |
| `GET/POST /spaces/{id}/transactions`, `GET/PUT/DELETE …/transactions/{txnId}` | expense, income, transfer; keyset pages, filtered by `from`, `to`, `accountId` or `categoryId` (with its subcategories); optional client `id` makes a create safe to retry; edits and deletes send the `version` they saw (409 if stale) |

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

`BalanceIntegrationTest` (core-service, against a fake ledger) proves balances are shown in the user's terms (a card's
debt as a positive amount owed), a space with nothing posted yet shows zeros, a ledger outage leaves accounts loading
without balances, and category totals read as positive spending and income.

`web/e2e/money.spec.ts` (Playwright, real stack) adds an account and an expense through the UI, waits for the ledger's
balance, edits and deletes the expense, and checks the balance after each step.

`scripts/money-smoke-test.sh` proves the whole chain on the running stack: an account and an expense go through the
gateway, core-service, Kafka and the ledger, an edit and a delete reverse correctly, and the balances come back.

`LedgerCommandIntegrationTest` proves core-service's commands arriving over Kafka are posted once even when
delivered twice, an edit (reverse + new version) nets out correctly, and commands that can never succeed (bad JSON,
unbalanced, unknown type) go to the dead-letter topic without blocking the commands behind them. Transient failures are
retried forever, in order: a dead-lettered command would be a user's money that never reached the ledger.

`OutboxIntegrationTest` proves a committed entry reaches Kafka keyed by its book, a rejected one leaves no event,
an idempotent replay emits nothing, and the outbox can't be written outside the business transaction.

`GatewaySecurityTest` proves anonymous API calls get 401 (not a redirect), login uses PKCE and `?signup` opens
registration, the session cookie is `HttpOnly` + `SameSite=Lax`, signed-in calls reach core-service with a bearer token
and without browser cookies, and logout requires the CSRF token and ends the Keycloak session.

`ProfileIntegrationTest` (core-service) proves a first request creates the user with Indian defaults and one personal
space (also under 10 concurrent first requests), settings and UI preferences are validated, and unverified emails are refused.

`MoneyIntegrationTest` (core-service) proves new spaces get the default categories; opening balances, expenses, income
and transfers queue balanced ledger commands with the right signs (a card is a liability); a money edit reverses the
live version and posts the next while a note edit leaves the ledger alone; 8 concurrent retries of one create add it
once; 8 concurrent edits of one version let exactly one win; pages have no gaps or duplicates; other people's spaces
answer 404; and the commands reach Kafka keyed by space.

`CategoryIntegrationTest` (core-service) proves names are unique per kind ignoring case (also under 8 concurrent
creates), subcategories stay one level deep and of the same kind, hiding a parent hides its subcategories and stops new
transactions in them while old ones stay editable, and a category any transaction ever used (even a deleted one) can't
be deleted.

`BudgetIntegrationTest` (core-service, with a fake ledger) proves spending, remaining and status per budget, a parent
counting its subcategories, a limit change applying from its month on while earlier months keep theirs, periods that
follow the user's start day, limits still showing when the ledger is down, and bad budgets refused; and that safe to
spend takes bills still due and savings off what is left of income.

`CommitmentIntegrationTest` (core-service) proves due dates (the 31st, an EMI's last instalment, quarterly), that paying
records an expense or transfer once (also under 8 concurrent taps), that deleting the payment makes the date due again,
optimistic locking on edits, and that a category a bill uses can't be deleted.

`PlannerIntegrationTest` (core-service) proves yearly and one-off costs are spread over the months left while ordinary
bills aren't planned, that set-asides come off safe to spend, and that in its own month a cost that was set aside for
counts neither as a bill still due nor, once paid, as spending.

`ImportIntegrationTest` (core-service) proves the preview checks every row and saves nothing, unknown names can be mapped
or created, a bank statement with debit and credit columns imports, importing the same file again adds nothing, undo
deletes what an import added (and then it imports again), a 2,000-row file imports in one request, and the Excel template
has dropdowns of your names and imports once filled in (real Excel dates, numbers and formulas included). `ImportParserTest`
covers Indian amounts (₹1,00,000.50, (500)), day-first dates (05/10/26, 05-Oct-2026) and quoted CSV cells.

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
| 3. Foundations | Ledger v2 (books, account types, reversals, period totals) ✅ · Keycloak, BFF gateway, core-service (users, spaces) ✅ · web app: theme builder, drag-and-drop dashboards, settings ✅ | ✅ |
| 4. Personal budgeting | Accounts, transactions, categories, budgets, dashboard, reports, CSV import | ⏳ |
| 5. Splitting | Groups, invites, join links, guests, split types, settle up, simplify debts | ⏳ |
| 6. Engagement & SaaS | Notifications, budget alerts, recurring, goals, data export, account deletion | ⏳ |
| 7. Operability | Tracing, dashboards, load tests, cloud deployment | ⏳ |
