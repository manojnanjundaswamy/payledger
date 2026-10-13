# Product plan: personal budgeting with group splitting

**Status:** Draft for review · **Replaces:** the wallet roadmap (deposits and transfers are retired)

**Decided so far:** India first, global-ready data model · dashboard and analytics only (no payment gateways, no UPI
payments, no bank sync yet) · import through our own template format · households in scope · Keycloak · one core-service ·
web app first · product name not chosen yet (candidates in [naming.md](naming.md))

## 1. The product in one paragraph

A SaaS web app where people sign up with their email, track **all of their money** (accounts, income, spending,
budgets, goals, reports), and **split shared costs** with anyone, whether or not that person has an account. A friend
gets a link by email or chat and can see what they owe, add expenses and record payments, as far as the group's
permissions allow. Splitting and budgeting are connected: when a ₹3,000 dinner is split three ways, **only your ₹1,000
share** counts against your "Eating out" budget, and the ₹2,000 you fronted shows up as money owed to you, not spending.

### Who it's for
- **Individuals** who want a clean budget without a spreadsheet.
- **Flatmates, couples, trips and friend groups** who share costs and want to settle up.
- **Households**: a spouse, children, parents living together (or supported from afar) who want one combined budget
  while keeping some money private.
- **Guests**: people pulled into a group by a link, who should never be forced to sign up.

### The problems we solve
Built around how money actually moves in Indian homes, not around a generic spreadsheet:

| Pain point | What people do today | What we do |
|---|---|---|
| **Dozens of small UPI payments vanish.** ₹40 chai, ₹180 auto, ₹99 subscription; nobody knows where ₹15,000 went. | Nothing, or a notes app | Two-tap quick add, an "uncategorised" inbox to clean up weekly, bulk import from a template |
| **"Salary came on the 1st, broke by the 20th."** | Check the bank balance and hope | **Safe to spend**: what's left today after bills, EMIs, SIPs and savings goals that are still due this cycle |
| **Big yearly costs ambush the budget**: insurance premium, school fees, Diwali, weddings, car service, property tax | Panic month, credit card | **Annual planner**: list them once; the app sets aside a monthly amount for each and warns months ahead |
| **EMIs and credit card bills pile up** | Separate reminders everywhere | Commitments view: every EMI, card due date and subscription in one calendar, counted before "safe to spend" |
| **Household spending is invisible to the household.** One partner pays rent, the other groceries; nobody sees the total | Arguments, WhatsApp messages | Household space with a combined budget, per-member contributions, and private money that stays private |
| **Monthly bills with attendance**: milk, newspaper, cook, maid; days missed change the bill | Paper calendar on the fridge | **Daily services tracker**: mark missed days, the month's bill is calculated, the payment becomes a transaction |
| **Money lent to friends and relatives** (udhaar) is forgotten | Memory | Lending tracker on the same receivables as splitting, with polite reminders |
| **Supporting parents or siblings** | Untracked transfers | "Family support" category and goal, visible in household reports |
| **Tax season scramble** (80C, 80D, HRA, home loan interest) | Hunting for receipts in March | Tag tax-relevant spending as it happens; **financial-year (April–March) reports** |
| **Splitting with friends is awkward**, and Splitwise's share doesn't reach your budget | Two apps, double counting | Split once; your share lands in your budget automatically |
| **No idea if a big purchase is affordable** | Gut feeling | **"Can I afford it?"**: enter ₹60,000 and a date; see the effect on safe-to-spend, goals and the emergency fund |

### How we help people decide better (not just record)
- **Spending pace**: "At this rate you'll spend ₹38,000 on food this month, ₹6,000 over budget", shown from the 5th, not on the 30th.
- **Monthly money review**: a 3-minute guided summary at the start of each cycle (what changed, what went over, one
  suggestion), done together in a household.
- **Budget suggestions** from your last 3 months, instead of an empty form.
- **Subscription and price-creep finder**: repeating amounts, and bills that quietly went up.
- **Health score** with plain-language reasons: emergency fund months covered, savings rate, share of income on EMIs.
- **Nudges, not nagging**: at most one insight a day; every alert can be tuned or muted.

## 2. Features

### 2.1 Personal budgeting (registered users)
| Feature | MVP | Later |
|---|---|---|
| **Money accounts** | Bank, cash, credit card and wallet accounts with an opening balance; balance is always computed from the ledger | Loans, investments, net worth over time |
| **Transactions** | Expense, income, transfer between your own accounts; category, tags, notes, date; edit and delete | Attachments (receipts), split one transaction across several categories |
| **Categories** | Default set, fully customisable: name, icon, colour, subcategories, hide or merge | Rules ("Swiggy → Eating out") |
| **Budgets** | Monthly limit per category or category group; progress bars; alerts at 80% and 100% | Rollover of unspent amounts, zero-based / envelope mode, yearly budgets |
| **Budget period** | Month can start on any day (for example payday, the 25th) | Weekly or custom periods |
| **Recurring** | Recurring bills and income with reminders | Auto-post |
| **Goals** | Savings goals with a target and date | Linked to a savings account |
| **Reports** | Spending by category, budget against actual, monthly trend, cash flow (income − spending), what you owe or are owed | Custom report builder, year in review |
| **Import / export** | Import from our **template** (CSV, Excel or Google Sheets copy; see section 2.5) with preview, error report and duplicate detection; CSV and Excel export | Parse bank and card statements (PDF or CSV) into the template, then bank sync (Account Aggregator in India) |
| **Commitments** | EMIs, credit card due dates, SIPs, insurance, subscriptions in one calendar; **safe to spend** | Reminders by email |
| **Annual planner** | Yearly and one-off costs spread into monthly set-asides | Festival and wedding planning templates |
| **Daily services** | Milk, newspaper, cook, maid: rate per day or month, mark missed days, bill calculated | |
| **Lending (udhaar)** | Track money lent or borrowed outside groups, with due dates | Reminders |
| **Tax tags** | Tag 80C / 80D / HRA / home-loan items; financial-year (April–March) report | Export for your CA |
| **Insights** | Spending pace, "Can I afford it?", budget suggestions, health score | Subscription and price-creep finder, monthly review |

### 2.2 Splitting (registered users and guests)
| Feature | MVP | Later |
|---|---|---|
| **Groups** | Create a group (trip, flat, couple…), default currency, cover image or emoji | Archive, templates |
| **People** | Invite by email; share a join link; add a **placeholder** member (name only) and invite them later | Friends list, one-to-one expenses outside groups |
| **Expenses** | Split equally, by exact amounts, by percentage or by shares; one or several payers; category; edit and delete with history | Item-by-item splitting of a bill, receipt scan |
| **Balances** | Per person in the group, and across all your groups | Multiple currencies with conversion |
| **Simplify debts** | Reduce the group's balances to the fewest payments | |
| **Settle up** | Record a payment (cash, UPI, bank) between two members | "Pay with UPI" button (`upi://pay` link with the amount filled in), payment reminders |
| **Activity** | Group activity feed, comments on expenses | Reactions, push notifications |

### 2.3 Where the two meet
- Your **share** of a group expense becomes a transaction in your personal book, in the category chosen on the expense
  (or your own mapping, so "Food" in the group can count as your "Eating out").
- What others owe you, or what you owe them, is a **receivable or payable**, not spending or income.
- Settling up moves money between your bank account and that receivable or payable. It never touches your budget.
- Guests have no personal book. When a guest signs up with the same email, their memberships attach to the new
  account and their shares flow into their budget from then on.

### 2.4 Households
A **household** is a shared space for people who manage money together: spouse, children, parents, a sibling.

| Topic | Design |
|---|---|
| **Spaces** | Every user has a **personal space**. A household is a second space they belong to. One person can be in more than one household (their own, and their parents'). |
| **Members** | Adults with their own login; **dependents** without a login (children, elderly parents) who can still have accounts, pocket money and spending assigned to them. A dependent can later be given a login. |
| **Roles** | Head (manages members and settings), Partner (full access to shared money), Member (adds transactions, sees shared budgets), Viewer (e.g. a teenager: sees, cannot change). |
| **What's shared** | Each money account is either **shared** (joint account, house cash, a card used for home costs) or **personal**. Shared accounts and their transactions belong to the household. |
| **Privacy** | Personal accounts stay private. A member can choose to share a **summary** ("Manoj: ₹12,400 on food this month") without sharing the transactions. |
| **Contributions** | Who paid how much into household costs this month, against an agreed split (50/50, by income ratio, fixed amounts). It uses the same engine as group balances, so "who owes the household" is exact. |
| **Budgets** | Household budgets per category on top of personal ones. Spending from a personal account can be marked "for the household" and counts in both views, without double counting. |
| **Reports** | Combined, per member, and per dependent ("how much does our daughter's schooling cost a year?"). |

Households differ from **groups**: a group is for people who split costs and settle up (a trip, flatmates); a household
pools money and plans together. A household can still use splitting internally through contributions.

### 2.5 Import format
We define one well-documented template, so any source (a bank statement, an old spreadsheet, another app's export)
can be converted to it once, by the user or later by our own parsers.

- **Formats**: CSV (UTF-8), Excel `.xlsx` (with dropdowns for category and account, and an instructions tab), and a
  shareable Google Sheets copy. Download the template pre-filled with *your* accounts and categories.
- **Columns** (required in bold): **date**, **amount**, **type** (`expense` / `income` / `transfer`), **account**,
  category, subcategory, description, merchant, payment_mode (`UPI` / `cash` / `card` / `netbanking` / `wallet`),
  to_account (for transfers), tags, notes, space (personal or household name), member, group, split_with, currency, external_id.
- **Indian-friendly parsing**: dates as `DD-MM-YYYY`, `DD/MM/YY` or ISO; amounts like `₹1,00,000.50`, `1,00,000` or
  `(500)` for negatives; Dr/Cr columns instead of signed amounts.
- **Safe imports**: a **preview** with every row validated before anything is saved; a downloadable **error report**
  (row, column, reason); unknown categories or accounts can be mapped or created in one step; re-importing the same
  file is a no-op (duplicates detected by `external_id`, or by date + amount + description); every import can be **undone** as a batch.
- The same format is available as a JSON bulk API, so future statement parsers just produce template rows.

### 2.6 SaaS basics
- Sign up and log in with email (one-time code or password) or Google; email verification; password reset.
- Account settings, sessions, **export all my data**, **delete my account** (required under GDPR and India's DPDP Act).
- Plans are **not** charged in the MVP, but data limits and feature checks go through one place, so billing can be
  added later without touching every feature (see section 9).
- **India first, global-ready**: INR, `en-IN` formatting (₹1,00,000), April–March financial year and Indian categories
  by default. Underneath, every amount carries a currency, every user has a locale, time zone and base currency, and all
  UI text sits in translation files, so other countries and currency conversion are configuration and data, not a rewrite.

## 3. Guests and permissions

The hardest product requirement, so it is designed up front.

### 3.1 Ways into a group
| Way in | How it works | Identity |
|---|---|---|
| **Email invite** | Owner enters an email. The guest gets a personal link and opens the group with no sign-up. | Verified: they control that inbox |
| **Join link** | Owner shares one link (WhatsApp, Slack). The visitor picks their name from the placeholder members or enters name + email, then confirms a 6-digit code sent to that email before they can change anything. | Verified by code |
| **Placeholder** | Owner adds "Rahul" with no email; expenses can include him at once. Later he claims the spot through an invite or the join link. | None until claimed |
| **Registered user** | Normal login; the group appears in their account. | Keycloak |

### 3.2 Guest sessions
- Links carry a random token; the server stores only its **hash**, with an expiry (14 days), and the owner can revoke it.
- Opening a link creates a **guest session** (an httpOnly cookie, 30 days) that is valid **only for that member in that
  group**. A guest in two groups has two scopes, merged under one session when the email is the same.
- A guest who lost the link enters their email on the "get a new link" page and receives a fresh one.
- Every guest page offers "Create an account to track your own budget", which keeps the same email and memberships.

### 3.3 Roles and group settings
| Role | Can do |
|---|---|
| **Owner** | Everything, including delete group and transfer ownership |
| **Admin** | Manage members, links and settings |
| **Member** | Add expenses, edit their own, record settlements they are part of |
| **Viewer** | See expenses and balances only |

Group settings the owner can toggle: *anyone with the link can join* · *new members need approval* ·
*members can edit anyone's expenses* · *guests can add expenses* · *guests can record settlements*.
Defaults: invited guests are **Members**; people joining by link are **Members** after email confirmation.

Every change is recorded in the group's activity history (who, what, before and after), so a disputed edit can always be traced.

## 4. Architecture

```
Browser (React SPA, installable as a web app)
   │  HTTPS
   ▼
API gateway ── checks the token (Keycloak for users, guest session for guests), rate limits (Redis), routes
   ├──▶ core-service        profiles, money accounts, transactions, categories, budgets, goals,
   │                        groups, members, invitations, expenses, settlements, imports       [Postgres: core]
   │        └─ outbox ──▶ Kafka  core.events.v1
   ├──▶ ledger-service      generic double-entry engine: books, accounts, entries, balances      [Postgres: ledger]
   │                        consumes core.events.v1, posts balanced entries idempotently
   │        └─ outbox ──▶ Kafka  ledger.entries.v1
   └──▶ notification-service   emails (invites, links, reminders, budget alerts), activity feed  [Postgres: notifications]

Keycloak (users, email login, Google)  ·  Mailpit locally / SES or Resend in production  ·  MinIO / S3 for files later
```

### 4.1 Responsibilities
- **core-service** (the agreed "one new service"; renamed from `splits-service` because it now covers budgeting too).
  Owns everything users see and edit. Internal modules with no shared tables between them: `profile`, `money`
  (accounts, transactions, categories, recurring, import), `budget` (budgets, goals), `split` (groups, members,
  invitations, expenses, settlements), `access` (roles, guest sessions, links).
- **ledger-service** stays the **only source of truth for balances**. It knows nothing about budgets or groups: it keeps
  *books* (one per personal space, one per household, one per group), *accounts* in each book, and balanced *entries*. It answers questions like
  "balance of these accounts" and "totals per account per month".
- **notification-service** keeps its current role and grows from "feed of transfers" to email and in-app notifications.

### 4.2 Why one ledger fits both budgeting and splitting
Personal finance is double-entry accounting (as in GnuCash or Beancount). Categories are expense or income accounts,
so a budget is a limit on an expense account for a period. All examples below are in your personal book; every entry sums to zero:

| Event | Postings |
|---|---|
| ₹500 groceries on credit card | Expense:Groceries **+500**, Liability:Credit card **−500** |
| Salary ₹80,000 | Asset:Bank **+80,000**, Income:Salary **−80,000** |
| You pay a ₹3,000 dinner for 3 from the bank | Expense:Eating out **+1,000**, Receivable:Goa trip **+2,000**, Asset:Bank **−3,000** |
| Rahul pays you back ₹1,000 by UPI | Asset:Bank **+1,000**, Receivable:Goa trip **−1,000** |

In parallel, the **group book** tracks each member's net position (the Splitwise balance):
dinner → you **+2,000**, Rahul **−1,000**, Priya **−1,000**.

- Edits and deletes are a **reversal plus a new entry** (the ledger stays append-only), hidden from the normal UI but
  kept in the history.
- Rounding: amounts are integer minor units; the leftover paise of an uneven split go to members in a fixed order,
  so the same expense always splits the same way.
- Balances per currency; an entry must balance in each currency.

### 4.3 How a write flows
1. The browser calls core-service, for example "add expense".
2. core-service validates, saves the expense and an outbox event **in one transaction**, and returns at once.
3. The relay publishes to Kafka; ledger-service posts the entries, using the event id + version as its idempotency key.
4. The UI updates instantly (optimistic update); balances and budget totals refresh about a second later.

This reuses the outbox and Kafka flow already built and tested in Phase 2. If Kafka or the ledger is down, the user can
still add expenses; balances catch up when it recovers.

### 4.4 Technology
| Layer | Choice | Why |
|---|---|---|
| Backend | Java 21, Spring Boot 4, Postgres 17, Flyway, Kafka, Redis | Same as now |
| Identity | Keycloak (OIDC) | Sign-up, email login, Google, password reset, sessions without writing auth code |
| Guest access | Hashed link tokens + scoped session cookie issued by core-service | Keycloak has no notion of a non-user guest |
| Gateway | Spring Cloud Gateway | Token checks for both issuers, routing, Redis rate limiting |
| Frontend | React + TypeScript + Vite, TanStack Router + Query, react-hook-form + zod | Fast SPA, typed end to end |
| UI kit | Tailwind CSS + shadcn/ui (Radix primitives), lucide icons | Accessible components whose code we own and restyle |
| Charts | Recharts | Simple, themeable |
| API client | Generated from each service's OpenAPI spec (orval) | Front and back cannot drift apart |
| Email | Mailpit locally; SES or Resend later | Every email is visible locally |
| Tests | JUnit + Testcontainers; Vitest + Testing Library; Playwright end to end | |

## 5. UI and customisation

Goal: calm, fast and personal. Three screens should feel great: **Dashboard**, **Add transaction**, **Group**.

### 5.1 Main screens
- **Dashboard**: this period's spending against budget, top categories, recent transactions, upcoming bills, "you are
  owed / you owe", goals. Built from widgets.
- **Transactions**: fast list with search, filters, saved views, bulk edit, inline category change.
- **Budgets**: one card per category with a progress bar, colour-coded (on track / close / over), and a month switcher.
- **Reports**: category breakdown, trends, cash flow, budget against actual; every chart drills down to its transactions.
- **Household**: combined budget, contributions by member, shared accounts, dependents, the monthly review.
- **Plan**: commitments calendar (EMIs, card dues, SIPs, subscriptions), annual planner, safe to spend, goals.
- **Groups**: list with your balance in each; inside a group, expenses, balances, "settle up" and activity.
- **Add expense** (a sheet, reachable from everywhere with one key): amount first, then description, category,
  account; one toggle turns it into a group split.
- **Guest view**: the group screen only, with a clear "what you owe" at the top and no navigation to features they don't have.

### 5.2 Customisation
- Light, dark or system theme; **accent colour**; compact or comfortable density; text size.
- Dashboard widgets can be shown, hidden and rearranged by drag and drop.
- Categories with your own names, icons, colours and subcategories.
- Currency, number format (including **lakh/crore** grouping), date format, first day of week, budget month start day.
- Default split method and default account.
- Keyboard shortcuts and a command palette (Ctrl/⌘ K) for everything.

All of this sits on **design tokens** (CSS variables for colour, radius, spacing and type). Themes and accent colours
only change token values, so customisation never forks components.

### 5.3 Quality bar
- Works well on a phone browser and can be installed as an app (PWA).
- Accessible: keyboard navigation, focus states, contrast checked in both themes.
- Empty states that teach, skeleton loading, optimistic updates, undo instead of "are you sure?" where possible.

## 6. What changes in the existing code

| Component | Change |
|---|---|
| ledger-service | Becomes a generic engine: **books** (personal, household, group); account types ASSET / LIABILITY / INCOME / EXPENSE / EQUITY; balance check per currency; **reversals**; per-period balance queries. The deposit and transfer API and the "user accounts never negative" rule are retired; the database-enforced invariants (balanced entries, append-only postings, idempotency, ordered locking) all stay. |
| Outbox and Kafka | Kept as is; core-service gets the same outbox. |
| notification-service | Consumes core events for emails and the activity feed. Its current transfer messages are replaced. |
| docker-compose | Adds Keycloak (and its database), Redis, Mailpit, gateway, core-service, frontend. |
| Production compose | Untouched until cloud deployment is planned. |

## 7. Build order

Budgeting first, because splitting writes into the personal book that budgeting creates.

| Phase | Delivers | Done when |
|---|---|---|
| **3. Foundations** | Ledger redesign (books, account types, reversals, period queries); spaces (personal + household) in the data model; Keycloak; gateway; core-service skeleton; frontend shell with design system, theming, login and sign-up | You can sign up, log in, switch theme and see an empty dashboard |
| **4. Personal budgeting** | Money accounts, transactions, categories, budgets, commitments and safe-to-spend, annual planner, dashboard widgets, reports v1 (incl. FY), template import and export | A month of real spending can be entered or imported and tracked against budgets |
| **4.5 Plans and what the market scan added** | Free and Premium with limits and a 30-day trial; budget rollover and "fill budgets from income"; net-worth chart; bill calendar; bill suggestions from repeated transactions | A user can try Premium, and Free limits are explained where they are hit |
| **5. Households and splitting** | Households (members, dependents, shared vs personal, contributions), groups, roles, email invites, join links, placeholders, guest sessions, all split types, settle up, simplify debts, lending, daily services | A guest with no account can open a link, add an expense and settle up; your share shows in your budget |
| **6. Engagement and SaaS** | Insights (spending pace, can I afford it, suggestions, health score), monthly review, emails and in-app notifications, budget alerts, recurring transactions, goals, tax tags, activity history, data export, account deletion, plan limits scaffold | |
| **7. Operability** | OpenTelemetry tracing, Grafana dashboards, k6 load tests, then cloud deployment | |

Each phase ends with passing tests (unit, Testcontainers, Playwright for the main flows) and an updated study guide.

## 8. Open questions
1. **Name**: shortlist in [naming.md](naming.md). "PayLedger" stays as the code name until then.
2. **Households in which phase?** Proposed: the space model (personal + household books) is built in Phase 3 so nothing
   is redone, the household UI ships in Phase 5 together with splitting.
3. **Pricing**: two plans (section 9, from the market scan); validate the limits and price with 10–20 real users before turning on billing.

## 9. Monetisation
Two plans, set after a scan of Goodbudget, Mint, Monarch and Spendee (features, business models and prices:
[market-and-pricing.md](market-and-pricing.md)).

| | Free | Premium (₹99/month or ₹799/year) |
|---|---|---|
| Personal budgeting, accounts, transactions, reports (incl. FY), export, full history | ✓ (up to 5 open accounts) | ✓ unlimited |
| Budgets / bills and savings on the plan / big yearly costs | 5 / 5 / 1 | unlimited |
| Imports | 3 a month | unlimited |
| Groups and splitting, guests | ✓ unlimited (this is how new users arrive) | ✓ |
| Household (one shared budget) | – | up to 6 members, dependents |
| Bank sync, statement parsing, insights, CA access (later) | – | ✓ |

A 30-day Premium trial needs no card. Limits stop adding, never viewing or exporting.

Principles: **splitting stays free** because every guest is a future user; we never sell data and never push financial
products inside the budget (trust is the product). Later options: annual reports for CAs, a B2B version for small
offices and hostels.
