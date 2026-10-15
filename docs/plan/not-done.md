# Not done yet (kept up to date)

What is still open after release v0.10.0 (2026-10-15), so nothing is lost. Tick or remove items as they ship.
The full roadmap is in [product-plan.md](product-plan.md) (section 7); the owner's own lists are
[owner-requests-2026-10-15.md](owner-requests-2026-10-15.md) (all 12 done) and [ideas-2026-10-b.md](ideas-2026-10-b.md).

## Needs the owner (blocks something live)

- [ ] **Real email on the live site.** Put the Gmail app password in `.env` (`SMTP_*`, see `docs/hosting.md`) and run
  `scripts/keycloak-sync.sh`. Until then sign-up verification, password reset, guest join codes, bill reminders and
  the weekly recap go only to the local Mailpit (http://localhost:8025), not to people's inboxes.
- [ ] **Google sign-in.** Create the OAuth client (links in `docs/hosting.md`), put `GOOGLE_CLIENT_ID` and
  `GOOGLE_CLIENT_SECRET` in `.env`.
- [ ] **Forwarding addresses for bank alerts.** OK to set up Cloudflare Email Routing for `in.manojnanjundaswamy.com`
  and the Worker (`deploy/cloudflare/email-worker.js`, `INBOUND_SECRET`, `CAPTURE_DOMAIN`).
- [ ] Change the demo account's password and the Keycloak admin password before sharing the site.
- [ ] Decide: prices, the product name ([naming.md](naming.md)), payments (Razorpay) before charging.
- [ ] Keycloak upgrade PR #33 (sign-up asks for the password after email verification; works, needs your OK).

## Product: from the roadmap, not built yet

- [ ] **Month in review**: a page for last month (in, out, kept, against the month before, categories that moved,
  budgets kept, biggest spends, no-spend days) and an in-app notice on the 1st. Designed; the endpoint was drafted
  but not merged.
- [ ] **Households, the rest of 2.4**: dependents without a login (children, parents) with spending assigned to them;
  marking an account shared or personal inside a household; sharing only a summary of personal spending; reports
  per member and per dependent.
- [ ] **Groups**: "new members need approval" (3.3); emails to guests about their group (they see it only when they
  open it); guests editing their own expense in the page (the API allows it; the page only adds and deletes);
  receipt photos; item-by-item bill splitting; multiple currencies in one group.
- [ ] **Transactions**: split one transaction across several categories; receipt attachments.
- [ ] **Reports**: custom report builder; year in review.
- [ ] **Bank sync** through Account Aggregator (needs a licensed partner; statements, SMS and forwarding cover it now).
- [ ] **Languages**: UI text in translation files, and currency conversion in reports (India first works today).

## Operations (Phase 7)

- [ ] Tracing (OpenTelemetry), Grafana dashboards, k6 load tests, then cloud hosting (today: this laptop through a
  Cloudflare Tunnel, see `docs/hosting.md`).
- [ ] The two Kafka containers use about 220% CPU each on this laptop; tune or slim before long-running hosting.
- [ ] Production database backups on a schedule (today: a dump before each release, see below).
- [ ] Open Dependabot branches (TypeScript 7, Kafka 4.3, nginx and Node images): triage per CLAUDE.md.

## How a release goes (for next time)

1. Feature branch → `develop`; full tests on staging (`scripts/env.sh staging deploy`, then `… e2e`).
2. Dump the production databases (core, ledger, Keycloak: `pg_dump -Fc`) into `../payledger-backups/<date>-before-<tag>/`, outside the repo.
3. Merge `develop` into `main`, tag `vX.Y.Z`, `scripts/env.sh production deploy`, check the live site, push.
