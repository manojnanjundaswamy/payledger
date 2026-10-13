# Getting people's data in, and features worth borrowing

Status: proposal for the owner (2026-10-14). Nothing here is built yet.

## The problem

Typing every expense, or filling our Excel template by hand, is where budgeting apps lose people. Most
quit in the first week. The goal: **a useful budget in the first ten minutes, and almost no typing after that.**
It also has to work **without a licence or a regulator's approval**, so it doesn't block going live.

## What's possible in India today

| Way in | Licence or approval? | Effort | Verdict |
|---|---|---|---|
| **Bank statement upload**: the PDF, CSV or Excel exactly as the bank gives it, auto-detected, password PDFs too | None | Medium | **Do first** |
| **Forward-to-PayLedger email address**: bank alert emails and e-statements, forwarded by a one-time Gmail filter | None. We receive mail; we never read their mailbox. | Medium | **Do second** |
| **Paste or share SMS**: paste many bank SMS at once, or "Share → PayLedger" from Android's Messages app (installed web app, Web Share Target) | None. No SMS permission. | Small | **Do first** |
| UPI app and wallet statements (PhonePe, Paytm, Google Pay exports) | None | Small per format | Next |
| Mutual fund statement (CAMS/KFintech consolidated statement PDF) for net worth | None | Medium | Later (with goals and investments) |
| Native Android app reading bank SMS automatically | Google Play: "SMS-based money management" is an allowed use, but every app is reviewed, and apps are refused | Large (native app) | Later; apply once there's an Android app |
| "Connect Gmail" to read bank emails | Google's restricted-scope review plus a yearly independent security assessment (CASA) | Medium, plus yearly cost | Not now. The forwarding address gets the same emails without it. |
| Account Aggregator (RBI's consent-based bank data sharing) | Only regulated entities (RBI, SEBI, IRDAI or PFRDA) can be data users | — | Not possible for us directly. Revisit with a regulated partner once there is a company. |

Sources: [Google Play SMS and Call Log permissions](https://support.google.com/googleplay/android-developer/answer/17225965),
[a developer's SMS-permission rejection](https://dev.to/zeta_byte/ive-been-working-on-a-personal-finance-app-called-finvantage-and-i-recently-hit-a-roadblock-4mjh),
[Gmail restricted scopes and CASA (Nylas)](https://developer.nylas.com/docs/cookbook/use-cases/build/google-oauth-scopes/),
[Google security review (Nango)](https://docs.nango.dev/integrations/google-security-review),
[Sahamati: who can be an FIU](https://sahamati.org.in/financial-information-user-fiu).

We still have duties under the DPDP Act 2023: a clear consent notice, use only for budgeting, and delete on
request. That needs care, not a licence. Statement passwords are used once and never stored. Uploaded and
forwarded files are deleted after parsing.

## The recommended flow: a budget in ten minutes

1. **Two-minute setup questions** right after sign-up:
   - Payday.
   - Rent.
   - EMIs and SIPs.
   - Tick the subscriptions you have (Netflix, Hotstar, Spotify, broadband, mobile, electricity).
   - We create the accounts, bills and a first budget from the answers. Safe to spend works on day one.
2. **"Bring your last 3–6 months"**: upload a statement, or paste SMS. The import already shows a preview and
   duplicates, and can be undone. These already work from that history:
   - **Category guesses**, which get better as you correct them.
   - **Bill suggestions**, from repeated payments (#51).
   - **Budget suggestions**, from the average of each category.
3. **Staying up to date without typing:**
   - Forward bank alerts once with a Gmail filter.
   - Or share an SMS to PayLedger.
   - Or quick-add like "250 swiggy".
   - New items land in a **"To review"** list. Swipe to accept the category. Nothing is booked twice: the
     import reference and a match on amount, date and account catch duplicates.

### What makes the guesses good (the real work)

- **A merchant list for India:** Swiggy, Zomato, Blinkit, IRCTC, BESCOM, Jio, Airtel, Uber, Ola, Amazon, Flipkart,
  and the rest. Each maps to a category.
- **UPI handles:** a handle like `swiggy@icici` names the merchant. A person's handle names a person (a possible
  split).
- **Your corrections become rules.** "Always put HUNGERBOX in Eating out" is a rule you can see, edit and apply
  to the past.
- **Bank formats:** one parser per bank, for both statements and alert messages: HDFC, SBI, ICICI, Axis, Kotak,
  IDFC First, Yes, IndusInd, BoB, PNB. Each one is tested against real anonymised samples.

## Features worth borrowing (beyond onboarding)

| From | Feature | Why it fits PayLedger |
|---|---|---|
| Copilot Money | "To review" list, and rules learned from corrections | The heart of low-effort tracking (above) |
| YNAB | Targets per category ("₹60,000 for Diwali by October"), "age of money" | Fits safe to spend and the annual planner; teaches the habit |
| Monarch | A weekly email recap; goals with progress; shared view for couples | Brings people back without opening the app (retention) |
| Splitwise | Itemised bill splitting from a receipt photo; recurring group expenses (flat rent) | Our groups already post to the ledger; this finishes them |
| Goodbudget | Envelopes shared live by a couple | Our Household phase |
| CRED / bank apps | Card and bill due reminders on WhatsApp or email | India-first reminders, where people actually look |
| Spendee | Shared wallets in many currencies | Trips abroad in groups (later) |

**Unique to PayLedger:**
- Split with friends, and your share lands in your own budget. Your share counts as spent; what friends owe
  you counts as owed, not spent.
- Settle up with a UPI link.
- Safe to spend that counts bills and big yearly costs.
- India's April–March financial year everywhere.

## Proposed order (one PR each)

1. Setup questions after sign-up, plus budget suggestions from history.
2. Bank statement import: auto-detected formats for the top banks (CSV/Excel first, then PDF with password).
3. Paste SMS (many at once), plus a parser for each bank's alert text.
4. A "To review" list and category rules (learned from corrections, editable).
5. Installable web app: Web Share Target for SMS and statements, and quick-add from the home screen.
6. Forwarding email address per user (inbound email service), parsing alert emails and e-statements.
7. Weekly recap email and due reminders.
8. Later: UPI and wallet exports, mutual fund statements, receipt photos, a native Android app with SMS (Play review).

## Decisions for the owner

- **Plans:** imports and auto-capture are Free with the existing limit (3 imports a month)? Or is ongoing
  auto-capture (forwarding address, SMS share) a Premium feature? Proposal: the first import and the setup
  are free for everyone (that's what keeps people). Ongoing automatic capture is Premium.
- **Inbound email:** needs a domain and an email service (for example Amazon SES inbound, Postmark or
  Mailgun). That means choosing the product name and domain first (`docs/plan/naming.md`).
- **Sample files:** real (anonymised) statements and SMS from the banks you and your friends use make the
  parsers reliable. Can you collect a few?
