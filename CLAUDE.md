# PayLedger: notes for Claude Code

Personal budgeting + household budgets + group splitting, **India first** (₹, lakh/crore, UPI-style
categories, April–March FY), global-ready. A SaaS where people sign up by email; guests can join
splitting groups by link without an account. Owner: Manoj. Read these before changing anything:

- Product plan (features, guests, households, roadmap): `docs/plan/product-plan.md`
- Market scan (Goodbudget, Mint, Monarch, Spendee) and the Free / Premium plans: `docs/plan/market-and-pricing.md`
- Name ideas (undecided, "PayLedger" is the code name): `docs/plan/naming.md`
- Design decisions: `docs/adr/0001`–`0008` (ledger, locking, idempotency, outbox, ledger v2 books, identity/BFF, groups and splitting, admin console)
- Event contract: `docs/events/ledger-entry-posted.md`
- How to run everything: `README.md`

## Architecture (what exists)

| Path | What | Stack | Ports (app / actuator) |
|---|---|---|---|
| `web/` | React app: dashboards, accounts, transactions, categories, budgets, plan, import (CSV/Excel), reports + groups (real data, `web/src/pages/money`), theme builder, settings | React 19, Vite, TS, Tailwind 4, TanStack Router+Query, dnd-kit, motion, Radix | 5173 |
| `services/gateway` | Only thing the browser talks to: OAuth2 login (Keycloak, PKCE), Redis sessions, CSRF (SPA mode), token relay | Spring Boot **4.0.8** + Spring Cloud Gateway 2025.1 (held on 4.0 until Spring Cloud 2026.0 GA) | 8000/8001 |
| `services/core-service` | Product API: users, spaces (`/api/core/me`), money accounts, categories, transactions, budgets, commitments, safe to spend, imports (`/api/core/spaces/{id}/…`); ledger commands via outbox -> Kafka `ledger.commands.v1` | Spring Boot 4.1, JDBC (JdbcClient), Flyway, resource server | 8060/8061 |
| `services/ledger-service` | Internal double-entry engine: books (PERSONAL/HOUSEHOLD/GROUP), typed accounts by caller ref, reversals, period totals, transactional outbox -> Kafka `ledger.entries.v2` | Spring Boot 4.1, JPA + JdbcClient | 8080/8081 |
| `services/notification-service` | **Parked** (reads wallet-era v1 events); becomes emails/activity feed in Phase 6 | | 8090/8091 |
| Keycloak 26 (realm in `deploy/local/keycloak`), Mailpit, Redis, Kafka (KRaft), Postgres x4 | `docker-compose.yml` | | 8180, 8025, 6379, 9092 |

Browser -> web (nginx or Vite proxy) -> gateway -> core-service -> (ledger, async via Kafka later).
The browser never holds a token. The ledger has no browser route.

## Run & test

```bash
docker compose --profile web up -d --build      # app at http://localhost:5173, demo@payledger.local / demo-password
docker compose --profile app up -d --build      # everything
cd web && npm install && npm run dev            # hot reload (stop the web container first: same port)
cd services/<svc> && ./mvnw verify              # Windows: mvnw.cmd verify. Tests use Testcontainers (Docker must run)
cd web && npm test && npx tsc -b                # unit tests + typecheck
cd web && npm run e2e                           # Playwright against the running stack
scripts/login-smoke-test.sh                     # sign-in + sign-up via Mailpit, token relay, CSRF, logout (bash / Git Bash / WSL)
scripts/keycloak-sync.sh                        # once, if your Keycloak volume predates the admin console (adds payledger-core-admin)
scripts/smoke-test.sh                           # ledger entries, reversal, totals, outbox -> Kafka
scripts/money-smoke-test.sh                     # account + expense via gateway -> core -> Kafka -> ledger, edit, delete, balances
```

Emails (verification, reset) appear in Mailpit http://localhost:8025. Keycloak admin http://localhost:8180 (admin/admin).

## Conventions

- **Money**: integer minor units (paise), `long`/`number`, never floats. Format with `web/src/lib/money.ts` (`formatMoneyShort` = K/L/Cr).
- **Ledger amounts**: signed, positive = debit. Assets/expenses positive, liabilities/income/equity negative.
  Edits are reversals + new entry, never updates. Every write carries an idempotency key.
- **Errors**: RFC 9457 problem+json with a stable `code`.
- **Migrations**: Flyway, never edit an applied migration; add a new `V<n>__*.sql`.
- **UI preferences**: one tolerant JSON object on the user profile (`web/src/state/preferences.ts`, zod with
  `.catch()` defaults). Change it only through `prefsReducer` (unit-tested). Saved debounced to `PATCH /api/core/me`.
- **Theme**: CSS variables in `web/src/styles.css`, written by `web/src/theme/engine.ts`. Monochrome default;
  user accent is contrast-corrected per mode. Charts use the validated `--series-*` palette; status = icon + text.
- **Comments** explain *why*. Docs and UI copy: plain, short, no jargon.
- **Tests** prove behaviour against real infrastructure (Testcontainers / the compose stack), including races.

## Workflow (agreed with the owner)

- Work on a feature branch, open **one PR per piece of work**, keep CI green, and **merge it yourself when green**
  (merge commit). The owner only decides product questions.
- **Dependabot**: triage it yourself. Merge when CI covers the change; close bumps that contradict decisions
  (Java stays 21, Postgres 17 everywhere) with a short reason. Image bumps in `docker-compose.yml` aren't covered by CI:
  run the stack + `scripts/login-smoke-test.sh` before merging (PR #12 is waiting on exactly that).
- Ask before major scope changes; otherwise make sensible defaults and say what you assumed.

## Next: Phase 4, personal budgeting (see the plan, section 7)

Money accounts, transactions (quick add, edit = reversal), categories, budgets with status and spending pace,
commitments + safe to spend, annual planner, template import (CSV/Excel, Indian formats, preview, undo),
reports incl. FY. core-service posts to the ledger via an outbox -> Kafka command topic (ledger consumer still to
build); the web app replaces sample data with real data per widget. Households and splitting are Phase 5.
