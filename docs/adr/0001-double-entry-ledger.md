# ADR 0001: Model money as a double-entry ledger, not a balance column

**Status:** Accepted

## Context
The naive design is `UPDATE wallets SET balance = balance - 25 WHERE id = ?`. It loses history,
can't explain *why* a balance is what it is, and a bug can silently create or destroy money.

## Decision
- Every business event is a **journal entry** with ≥ 2 **postings** (signed amounts) that **sum to zero**.
- Deposits come from a per-currency `SYSTEM` funding account, which is allowed to go negative.
  That negative balance is exactly what the platform owes the outside world.
- `accounts.balance` is a **cache** of `SUM(postings.amount)`, updated in the same transaction.
  Tests assert the cache always equals the sum.
- Amounts are `BIGINT` minor units. `double` cannot represent 0.10 exactly, and `BigDecimal` is
  unnecessary when every currency has a fixed number of decimal places.
- Postings are append-only. Mistakes are fixed with a `REVERSAL` entry, never an UPDATE.
- The database enforces the invariants itself (deferred constraint trigger, CHECK constraints,
  append-only triggers), so they hold even if a future code change has a bug.

## Consequences
- ➕ Full audit trail; any balance can be recomputed and explained.
- ➕ Reconciliation reduces to "sum of all postings = 0".
- ➖ More rows per operation, and a hot account (e.g. the funding account) serialises writes.
  Mitigation if it ever matters: shard the system account into N sub-accounts.
