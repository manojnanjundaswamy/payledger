# Not done yet (kept up to date)

What is still open after release v0.11.0 (2026-10-15), so nothing is lost. Tick or remove items as they ship.
The full roadmap is in [product-plan.md](product-plan.md) (section 7); the owner's own lists are
[owner-requests-2026-10-15.md](owner-requests-2026-10-15.md) (all 12 done) and [ideas-2026-10-b.md](ideas-2026-10-b.md).

## Needs the owner (blocks something live)

- [ ] **AI key.** Put `AI_API_KEY` (and `AI_PROVIDER`, `AI_MODEL`, the prices) in `.env` (see `docs/ai.md`), then
  `scripts/env.sh production deploy`. Until then quick add gives a simple reading and bank messages go by the rules only.
  Optional: `STT_API_KEY` (OpenAI or Groq) so Firefox users can speak entries too.
- [ ] **Delete the Cloudflare API token** you pasted in chat (My Profile, API Tokens) now that email is set up.
- [x] Real email on the live site (Gmail app password), Google sign-in, forwarding addresses (in.manojnanjundaswamy.com).
- [ ] Change the demo account's password and the Keycloak admin password before sharing the site.
- [ ] Decide: prices, the product name ([naming.md](naming.md)), payments (Razorpay) before charging.
- [ ] Keycloak upgrade PR #33 (sign-up asks for the password after email verification; works, needs your OK).

## Product: from the roadmap, not built yet

- [ ] **Households, the rest of 2.4**: dependents without a login (children, parents) with spending assigned to them;
  marking an account shared or personal inside a household; sharing only a summary of personal spending; reports
  per member and per dependent.
- [ ] **Groups**: "new members need approval" (3.3); emails to guests about their group (they see it only when they
  open it); guests editing their own expense in the page (the API allows it; the page only adds and deletes);
  receipt photos; item-by-item bill splitting; multiple currencies in one group.
- [ ] **Transactions**: split one transaction across several categories; receipt attachments.
- [ ] **Reports**: custom report builder; year in review.
- [ ] **Android app that reads bank SMS** (Play Store allows it for money management): the only capture with no setup.
  A thin app around the web app; the AI reader is ready for it.
- [ ] **Forwarded HDFC emails are refused** ("550 5.7.1 DMARC"): Gmail rewrites HDFC's broken Message-ID, which breaks
  HDFC's signature, and Cloudflare Email Routing enforces HDFC's reject policy. Axis works. Forwarding is no longer
  recommended; paste or share the SMS for such banks.
- [ ] **Remember which account a card or account number is**: once you add an alert for "ending 0474" to an account, use
  that next time (today it's the account name's digits, or the only account at that bank).
- [ ] **Bank sync** through Account Aggregator (needs a licensed partner; statements, SMS and forwarding cover it now).
- [ ] **Languages**: UI text in translation files (English only today; every currency, number format and financial
  year already works).
- [ ] **Multi-currency totals**: accounts kept in another currency on purpose show their own balance but are left out of
  totals in your main currency (net worth, safe to spend); converting them at today's rate in totals is next.
- [ ] **Emails in the reader's currency**: bill reminders, budget alerts, the weekly recap, group and insight messages
  are done; bank-statement summaries still say rupees (they come from Indian banks).
- [ ] **Push on iPhone**: works only when PayLedger is added to the Home Screen (Apple's rule); Android and desktop work
  from the browser. A notification's sound is the phone's own (browsers can't set one); the chime plays in the app.

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
