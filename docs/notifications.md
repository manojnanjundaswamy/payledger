# Notifications: the bell, email, WhatsApp

## What reaches people

Everything that involves a person lands in their **bell** (top bar). Unless they turned that kind off in Settings →
Notifications, it is **emailed** too.

| Happens | Who hears | Email setting |
|---|---|---|
| Someone adds, edits or deletes a shared expense you're part of | everyone in it with an account, except whoever did it | Groups |
| A payment is recorded or undone between you and someone | both, except whoever recorded it | Groups |
| You're added to a group; someone joins one of yours | you; the group | Groups |
| Someone without an account is added by email | them: an invite with the join link | always (not a user yet) |
| Bills and savings due in the next 3 days | you, one digest a morning (8:00 IST) | Bills due soon |
| A bill due in the last week isn't marked paid | you, once per bill and date | Bills due soon |
| A budget passes 80%, and again when it goes over | you, once each a month | Budget alerts |
| A sign-in from a browser not used before | you (bell only) | — |
| Welcome after sign-up, test email | you | always |

**How fast it arrives**
- **Live:** the bell asks for news every 5 seconds while the tab is open and visible, and again on focus. A new
  item pops up with an "Open" button. Two people in the same group see each other's changes within seconds.
- **Next step, to drop the polling:** server-sent events behind a Postgres LISTEN/NOTIFY fan-out, so every core-service
  instance can push.

## How email works

```
change ──(same transaction)──> email_outbox ──(EmailSender, every 10 s)──> SMTP ──> inbox
```

- **Queued with the change:** an email is written to `email_outbox` in the same transaction as the change it is about.
  A slow or broken mail server never slows or breaks the app.
- **Sent in the background:** a job delivers the queue with `SKIP LOCKED`, so several instances never send the same
  email.
- **Retried:** a failure is tried again after 1, 2, 4, 8 and 16 minutes. After that it gives up and keeps its error,
  which shows in Admin → Emails.
- **Never sent twice:** each reminder has a dedupe key, so re-running the reminders can't repeat an email.
- **Who sends what:**
  - Keycloak sends the sign-in emails: verify email, reset password, a welcome from staff.
  - core-service sends everything else.
  - Both use the same `SMTP_*` settings.

### Why your sign-up email never arrived

Locally, every email goes to **Mailpit**, a catch-all inbox at http://localhost:8025. Nothing reaches a real
mailbox. The verification link for that user is waiting in Mailpit: open it from there and the user works.

### Real delivery: what it costs

| Provider | Price | Good for |
|---|---|---|
| **Gmail SMTP** with an app password | free, about 500 a day | trying it with real inboxes today |
| **Brevo** | free 300 a day, then paid plans | early users |
| **Amazon SES** | about $0.10 per 1,000 emails | production: cheapest at scale, best deliverability with a verified domain |
| Postmark, Resend, Mailgun | free tiers, then a few dollars a month | alternatives to SES |
| Your own mail server (Postfix) | free software | not recommended: new servers land in spam unless the IP and domain are well set up |

**To switch** (no code change):
1. Copy `.env.example` to `.env`.
2. Fill in the six `SMTP_*` and `MAIL_FROM` values. The file explains each one for Gmail.
3. Run `docker compose --profile web up -d`, then `scripts/keycloak-sync.sh` (which points Keycloak at the same server).
4. Send yourself a test email from Settings → Notifications. It shows as sent there, and in Admin → Emails.

For production, use a domain you own:
- Verify the domain with SES or Brevo, and set up SPF, DKIM and DMARC records.
- Send from `no-reply@yourdomain`.

## WhatsApp

**Now (free, built):**
- **Share on WhatsApp** next to a group's invite link.
- **Remind on WhatsApp** next to "who owes you", which opens WhatsApp with a friendly message already typed.

Both use WhatsApp's click-to-chat link (`https://wa.me/?text=…`). It needs no account or approval: the person picks
who to send it to.

**Automatic WhatsApp messages (later):** bill reminders and invites sent by PayLedger itself need the **WhatsApp
Business Platform (Cloud API)** from Meta.
- **Approval:**
  - a Meta Business account, verified with business documents
  - a phone number dedicated to the API
  - each message type pre-approved as a template ("Your {bill} of ₹{amount} is due on {date}")
- **Price:** charged per message by category:
  - utility messages (reminders) cost the least, on the order of ₹0.1–0.2 each in India
  - marketing messages cost several times more
  Check Meta's current rate card before planning.
- **How to connect:** directly with the Cloud API, or through an Indian partner (Gupshup, Interakt, AiSensy, Wati) that
  handles onboarding and templates for a monthly fee.
- **Consent:** people must opt in with their number and be able to stop.

  It plugs in as another channel next to email, using the same events and the same per-kind settings.

## Activity audit (for staff)

- **Sign-ins:** each Keycloak session is recorded once, with time, browser and IP.
- **Changes:** every change made through the API is recorded: method, route, ids and outcome (for example
  `POST /api/core/spaces/{spaceId}/accounts 201`). **Amounts and descriptions are never recorded.**
- **Where staff see it:** Admin → User activity (everyone; filter by sign-ins, changes or errors), and on each
  user's page.
- **Admin actions:** recorded separately, in Admin → Audit log.
- **Retention:** kept 180 days, then deleted (also the inbox).
