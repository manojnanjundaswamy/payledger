# ADR 0007: Groups and splitting

**Status:** Accepted

## Context
Phase 5 adds splitting (product plan 2.2–2.3): groups of people (a trip, flatmates, a couple) share expenses, see who
owes whom and settle up. Members can be registered users, people added by name or email who haven't joined yet, and
later guests who open a link without an account. A member's share must also reach their personal budget, and money
owed must show as owed, not as spending.

## Decision
- **Groups live in core-service**, next to spaces: `groups`, `group_members`, expenses, payers, shares and settlements.
  Access is by membership of the group (not of a space), checked on every request; someone who isn't a member gets
  404, like a space.
- **Members, not users.** Every person in a group is a `group_members` row with a display name. `user_id` is set for
  people with an account; a **placeholder** (added by name, or invited by email) has none. Inviting an email that already
  has an account adds that user at once; otherwise the placeholder is **claimed** when someone signs up with that
  (verified) email. Joining by **link** (a random token per group, which can be revoked and replaced) adds the signed-in
  user, or claims the placeholder with their email. The name shown is always the one given in the group, so inviting an
  email never reveals the name on someone's account.
- **One ledger book per group** (`group:<id>`, kind `GROUP`), one `ASSET` account per member (`member:<id>`), meaning
  "what this member owes the group". An expense debits each member's share and credits each payer's payment; a
  settlement credits the payer and debits the receiver. A member's balance is therefore positive when they owe and
  negative when they are owed, and the book always sums to zero. Edits and deletes are reversals, as for transactions.
- **Amounts are exact.** Shares are stored in paise and must add up to the expense. Equal, percentage and share splits
  give the leftover paise to members in their order, so every split is reproducible.
- **Personal books** (next PRs): a registered member's share becomes an expense in their own category, and what they
  are owed or owe is a receivable or payable account (`group:<id>`) in their personal book; settling up moves money
  between that account and a bank account, never touching the budget.
- **Splitting stays free** on every plan (it is how new users arrive).

## Consequences
- ➕ The same ledger guarantees (balanced, append-only, idempotent) hold for who-owes-whom.
- ➕ Placeholders and links let a group start before everyone has signed up, and guests (no account) fit later as
  members without a user.
- ➖ Balances come from the ledger, a moment after a write, as account balances already do.
- ➖ A member who has a balance can't simply be removed: they leave only once settled (their history stays).

## Update (2026-10-15): guests
Guests are built as planned: from the join link, someone without an account picks a waiting name (or gives theirs),
proves their email with a code and gets a guest session for the groups where that email is a member. Their expenses
and payments record the member who added them (`created_by_member`), as there is no user. They see the group, add
expenses (equal split) and settle up, and change only what they added. Signing up with that email turns the
memberships into the account's (the existing claim), and the guest session no longer reaches them.

