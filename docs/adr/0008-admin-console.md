# 0008: The admin console

Status: accepted (2026-10-13)

## Context

PayLedger needs a place where its own team runs the business: how many people use it, who pays, and
helping customers (finding them, resending an email, giving Premium, creating an account). The people using it
have different jobs, and the product promises "no ads, no data selling", so staff access must be limited
and visible.

## Decision

**One console, inside the web app, at `/admin`.** It appears in the navigation only for staff. Every API call
lives under `/api/core/admin/**` in core-service.

**Roles live in core-service, not in Keycloak.** Table `staff_roles(user_id, role)`. A change takes effect
on the next request, while a role inside a token would stay until the token expires. Managing staff then needs
no Keycloak admin rights. The emails in `ADMIN_EMAILS` are OWNER without a row, so there is always a way
in, even on an empty database. Locally that is the demo user.

**Least privilege. Each role gets only what its job needs:**

| Permission | Owner | Admin | Support | Analyst |
|---|:-:|:-:|:-:|:-:|
| Dashboard: counts, active users, plans, retention (`ANALYTICS_VIEW`) | ✓ | ✓ | ✓ | ✓ |
| Find users; see their profile, plan and usage counts (`USERS_VIEW`) | ✓ | ✓ | ✓ | |
| Resend emails, sign out everywhere (`USERS_SUPPORT`) | ✓ | ✓ | ✓ | |
| Create, disable and enable users (`USERS_MANAGE`) | ✓ | ✓ | | |
| Give, extend or end Premium (`PLANS_MANAGE`) | ✓ | ✓ | | |
| Free's limits, trial length, announcement (`SETTINGS_MANAGE`) | ✓ | ✓ | | |
| Read the admin audit log (`AUDIT_VIEW`) | ✓ | ✓ | | |
| Make someone staff, change or remove a role (`STAFF_MANAGE`) | ✓ | | | |

**Two checks on every call:**
1. An interceptor (`StaffGate`) turns away non-staff from `/api/core/admin/**` before the request body is
   read. A customer always gets `403 not-staff` and learns nothing about the admin API. A test calls every
   admin endpoint, including future ones, as a customer.
2. Each endpoint then calls `StaffService.require(user, permission)`. Staff whose role lacks the permission get
   `403 missing-permission`.

The web app hides what a role can't do (`useCan`), but the server is the authority.

**Staff never see a customer's money.** The console shows people (name, email, sign-up, last active), their
plan, and counts (accounts, transactions, groups). It never shows amounts, descriptions or notes. If support
ever needs more, the customer shares a screen or an export; staff don't look themselves.

**Activity is a day per user.** `user_activity_days(day, user_id)` gets one row the first time a user calls the
API on a day, in the business time zone (`BUSINESS_ZONE`, default Asia/Kolkata). It is written at most once
per user per day per instance, and the insert is idempotent. The table answers daily, weekly and monthly active
users and weekly retention. It records nothing about what the user did. `users.last_active_on` keeps the
latest day.

**Every admin write is audited.** Each write records who did what to whom, and when, in `admin_audit`, in the
same transaction as the change. The log is append-only: no endpoint edits or deletes it.

**Accounts go through Keycloak; passwords never pass through staff.** core-service calls Keycloak's admin API as
the confidential client `payledger-core-admin`. Its service account may only manage and view users of this realm.
- **Creating a user** makes the Keycloak account and the PayLedger user. Keycloak then emails a link to verify the
  address and choose a password.
- **Disabling** marks the user in core-service (`403 account-disabled` on every request), blocks sign-in in Keycloak and
  ends their sessions, in that order. If Keycloak fails, the whole change rolls back. Nothing is deleted.
- **Without the client secret**, these actions answer `503 identity-unavailable`. The rest of the console still works.
- Keycloak imports the realm file only once, so an older local volume needs `scripts/keycloak-sync.sh` once.

**Premium given by staff** is `premium_source = GRANTED` until the end of a chosen day, at most five years ahead.
It replaces a trial or an earlier grant, but never a paid subscription.

## Consequences

- Sign-ups are counted when the user first opens the app after verifying their email. A user who verifies but
  never opens the app isn't counted.
- Activity history starts with this change, so earlier days show no active users.
- In production, staff accounts should use two-factor sign-in. In Keycloak this is a conditional OTP flow for
  staff, set up when the production realm is created.
