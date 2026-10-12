# ADR 0006: Keycloak for identity, a backend-for-frontend gateway for the browser

**Status:** Accepted

## Context
PayLedger is becoming a SaaS app people sign up for with their email ([product plan](../plan/product-plan.md)).
We need registration, email verification, login, password reset and later Google login, and the money data behind it
must never be exposed to a stolen token.

## Decision
- **Keycloak** (realm `payledger`) owns identity: registration with email as username, email verification, password
  policy, brute-force protection, password reset, sessions. Local config is code: `deploy/local/keycloak/payledger-realm.json`
  is imported on first start; email goes to Mailpit.
- **The gateway is the only OAuth2 client** (backend-for-frontend). It runs the authorization-code flow with **PKCE**,
  keeps the tokens in a server-side **session in Redis**, and gives the browser only a `PAYLEDGER_SESSION` cookie that is
  `HttpOnly` and `SameSite=Lax` (and `Secure` behind HTTPS). JavaScript never sees a token, so an XSS bug can't steal one.
- **CSRF**: Spring Security's SPA mode. The gateway sets a readable `XSRF-TOKEN` cookie and the web app echoes it in
  `X-XSRF-TOKEN` on every write.
- **Token relay**: `/api/core/**` is forwarded to core-service with the user's access token (refreshed when it expires).
  The browser's cookies are stripped. The ledger has no route at all; only other services call it.
- **core-service is a stateless resource server**: it checks the token's signature, issuer and audience (`payledger-api`,
  added by a mapper on the gateway client) and refuses users whose email isn't verified. The first request of a new
  person creates their user row and personal space (race-safe).
- **One issuer, two networks**: tokens are issued as `http://localhost:8180/realms/payledger` (what browsers see), while
  containers fetch tokens and keys at `http://keycloak:8080` (`KC_HOSTNAME_BACKCHANNEL_DYNAMIC`).
- **`/oauth2/authorization/keycloak?signup`** opens Keycloak's registration form directly (`prompt=create`).
- **Logout** ends the gateway session and returns Keycloak's end-session URL, so the Keycloak session ends too.

## Options considered
| Option | Verdict |
|---|---|
| Tokens in the browser (SPA as a public OAuth client) | Simpler, but tokens in JavaScript are exposed to XSS; current browser-app guidance prefers a BFF. |
| Build our own auth (email OTP + JWTs) | Full control, but password storage, verification, reset, brute force and social login are easy to get wrong. |
| Hosted identity (Auth0, Cognito, Clerk) | Least work, but a paid dependency and harder to run fully locally. Keycloak can be swapped for one later; only the gateway and core-service configs know it. |

## Consequences
- ➕ Sign-up, verification, login, logout and token relay are tested end to end against real Keycloak (`scripts/login-smoke-test.sh`).
- ➕ Guests (Phase 5) fit the same model: the gateway will issue guest sessions too; services only ever see a token or a guest scope.
- ➖ The gateway runs on Spring Boot **4.0** with Spring Cloud **2025.1** (the latest GA, which doesn't support Boot 4.1 yet).
  Dependabot ignores the Boot minor there until Spring Cloud 2026.0 is GA.
- ➖ No issuer discovery at gateway startup (it would make startup depend on reaching Keycloak), so the ID token's issuer
  isn't cross-checked; tokens arrive over the trusted back channel.
- ➖ Rate limiting at the gateway is not built yet (Redis is in place for it).
- ➖ Google login needs real OAuth credentials: add a Google identity provider in the Keycloak admin console; no code changes.
