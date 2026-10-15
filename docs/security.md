# Security, tenant isolation and data integrity

What protects people's money data today, how it's tested, and what's still to do before going live. Read
this before adding an endpoint.

## Tenants

- **A tenant is a space.** Every money record belongs to one space. Everyone gets a personal space; households
  are shared spaces with roles.
- **Groups are their own tenant.** Only their members can see them.
- **One database, a `space_id` on every row.** This is the common model for SaaS at our size. It is cheap and
  easy to back up.

**Where isolation is enforced**

| Layer | What |
|---|---|
| Gateway | The browser never holds a token; it has an httpOnly, SameSite session cookie. Writes need the CSRF token. The gateway relays the user's own Keycloak token to core-service. |
| core-service, every space endpoint | `SpaceAccess.read` / `write` first. A space you're not a member of answers `404` (not 403), so ids leak nothing. Viewers can't write. |
| core-service, every lookup inside a space | Accounts, categories, transactions, commitments, budgets and imports are always looked up by `space_id` *and* id. Ids sent in a body (an account, a parent category) are checked to belong to the same space. |
| Groups | Member check on every call. A stranger gets `404`. Member ids in a body must belong to that group. |
| Ledger | Internal only (no browser route); books are addressed by `space:<id>` / `group:<id>`, which core-service derives itself. |
| Admin console | Staff only, before the body is read. Each endpoint has its own permission. Staff never see amounts or descriptions (ADR 0008). |

**How it's proven: `TenantIsolationIntegrationTest`**
- User B calls **every** space and group endpoint, including ones added later (they are found automatically).
- **Phase 1:** B uses A's space, group and record ids, with valid-looking bodies for every kind of write.
- **Phase 2:** B smuggles A's ids into B's *own* space and group: in the path, and in bodies (an account id, a
  parent category, group members).
- Nothing may succeed, and no answer may contain A's names or ids. A hash of all A's rows must be the same
  before and after, and none of B's rows may point at A's.
- A mutation check confirmed the test fails at once if the membership check is removed.

## Integrity

- **Double-entry ledger:** each entry sums to zero. Money is in paise (whole numbers, never floats).
- **Edits never rewrite history.** They reverse the old entry and post a new one (ADR 0001, 0005).
- **Idempotency keys** on every write: a retried request doesn't double-post (ADR 0003).
- **The transactional outbox** sends ledger commands only when the database change has committed, and resends
  until it is acknowledged (ADR 0004).
- **Optimistic versions** on transactions, commitments and group expenses. A stale edit gets `409 version-conflict`
  instead of silently overwriting.
- **Row locks** where a count matters (plan limits, ADR 0002).
- **Database constraints:** checks on amounts and kinds, unique names, foreign keys. Migrations are append-only (Flyway).
- **Audit log:** every admin action, append-only, in the same transaction.

## Abuse and the browser

- **Rate limits at the gateway, per user (or per IP before sign-in), counted in Redis:**
  - 600 API calls a minute, of which 120 writes. Over that: `429 rate-limited` with `Retry-After`.
  - If Redis is down, requests go through: a limit must never become an outage.
  - Settings: `payledger.rate-limit.*`.
- **Security headers from nginx** (`web/security-headers.conf`): a strict Content-Security-Policy, no framing (`frame-ancestors none`).
- **Keycloak:**
  - email verification, brute-force lockout, password policy
  - sessions end after 30 days idle
  - access tokens live 5 minutes
- **Errors are RFC 9457 problems with a stable code.** No stack traces reach the browser.
- **Guests (no account) in a group** (`GuestService`, `/api/guest/**`):
  - they prove their email with a 6-digit code: 10 minutes, 5 tries, 5 codes an hour per email; only hashes are kept
  - the session is a random token in an httpOnly, SameSite=Lax cookie (Secure when hosted) on `/api/guest` only, 30 days, stored as a hash
  - it reaches only groups where that email is a member without an account; other ids answer 404, like a stranger's
  - guests change only what they added (`not-yours`); people with an account sign in instead (`has-account`)
  - the gateway passes only that cookie on, never its own session, and still checks CSRF

## Before going live (gaps)

| Gap | Plan |
|---|---|
| HTTPS everywhere, `COOKIE_SECURE=true`, HSTS | At deploy (reverse proxy / load balancer) |
| Encryption at rest and backups | A managed Postgres with encrypted storage, daily backups and point-in-time recovery; a restore test each quarter |
| Secrets in env defaults (`local-dev-*`) | A secret manager; rotate the Keycloak client secrets and the ledger API key |
| Two-factor sign-in for staff | A conditional OTP flow in Keycloak for staff roles |
| "Delete my account" and "download all my data" (DPDP Act 2023) | Export exists; account deletion (erase personal data, keep only anonymous ledger totals) is on the roadmap |
| Postgres row-level security as a second wall | Possible later: set the user per transaction and add RLS policies on `space_id`. The application checks and the isolation test are the first wall. |
| Dependency and container scanning | Dependabot runs; add a container image scan (Trivy) to CI |
