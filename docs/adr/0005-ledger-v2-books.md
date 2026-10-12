# ADR 0005: Ledger v2, a generic double-entry engine with books

**Status:** Accepted · **Supersedes:** the wallet model of ADR 0001 (its invariants stay)

## Context
PayLedger pivoted from a wallet that *moves* money to a budgeting and splitting app that *records* it
([product plan](../plan/product-plan.md)). The ledger now has to hold a person's accounts and categories, a household's
shared money, and a group's who-owes-whom, all with the same guarantees.

## Decision
- **Books.** Every set of accounts that balance together is a book: `PERSONAL` (a person's space), `HOUSEHOLD`, `GROUP`.
  An entry posts inside exactly one book; the database rejects postings to another book's accounts.
- **Typed accounts.** `ASSET`, `LIABILITY`, `EQUITY`, `INCOME`, `EXPENSE`. Amounts are signed, **positive = debit**.
  A category is an `EXPENSE` (or `INCOME`) account, so a budget is a limit on an account's movement in a period.
- **Caller references, created on first use.** Books and accounts are addressed by the caller's ids
  (`space:<uuid>`, `category:<uuid>`). The first posting creates them (`INSERT ... ON CONFLICT DO NOTHING`, in sorted
  order so two first postings can't deadlock). Callers never pre-create anything or store ledger ids, which is what
  lets core-service post asynchronously. A reference keeps its type (and a book its kind) forever.
- **Business date.** Each entry has an `effective_date` (can be in the past) used by period totals; `created_at` stays the audit time.
- **Edits are reversals.** `POST /reversals` posts the exact negation, dated like the original, linked by
  `reverses_entry_id` (`UNIQUE`: at most one reversal per entry; reversals can't be reversed). An edit = reverse + post the new version.
- **Balanced per currency**, enforced at commit by the deferred constraint trigger (upgraded from "sum to zero").
- **Negative balances allowed.** The ledger records reality (an overdrawn account, a card balance), so the
  "never negative" rule of the wallet era no longer applies to new accounts.
- **Events.** Every entry (reversals included) emits `ledger.entry.posted` **v2** on `ledger.entries.v2`, keyed by
  **book**, so one book's events stay in order on one partition.
- **Legacy data kept.** Existing wallet accounts and entries move, unchanged, into one `LEGACY` book. Nothing can post to it.

## Consequences
- ➕ Budgeting, households and splitting share one engine and one set of tested invariants (balanced entries,
  append-only postings, idempotency keys, ordered locking).
- ➕ The ledger knows nothing about categories, budgets or groups; it can't drift from core-service's product logic.
- ➖ Meaning lives in references (`category:…`): core-service must keep them stable and never reuse one for another type.
- ➖ Period totals are computed from postings on each query. Fine for personal books; if it gets slow, add a
  per-account-per-month summary table maintained in the same transaction.
- ➖ The wallet endpoints (`/api/v1/accounts`, `/deposits`, `/transfers`) are gone, and notification-service, which read
  wallet events (v1), is parked until Phase 6.
