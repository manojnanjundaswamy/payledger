# ADR 0002: Pessimistic row locks, acquired in a consistent order

**Status:** Accepted

## Context
Two concurrent transfers from a wallet with $100, each for $80, must not both succeed (lost update).

## Options considered
| Option | Verdict |
|---|---|
| Optimistic locking (`@Version`) only | Correct, but under contention most transactions fail and must retry. Retries of money movements add complexity and latency spikes. |
| `SERIALIZABLE` isolation | Correct, but Postgres aborts conflicting transactions (serialization failures), so you still need retry logic, and it's harder to reason about. |
| Atomic conditional update `UPDATE … SET balance = balance - :x WHERE balance >= :x` | Great for a single account, but a transfer touches 2+ rows and we also need `balance_after` for each posting. |
| **`SELECT … FOR UPDATE`, ordered by id** | ✅ Chosen. Contending transfers queue instead of failing. |

## Decision
All money movements lock their accounts through **one** repository query:
`select a from Account a where a.id in :ids order by a.id` with `PESSIMISTIC_WRITE`.
Because every transaction locks rows in the same order, A→B and B→A can never wait on each other in a cycle,
so they **cannot deadlock**. The ordering is done by **Postgres** in SQL, not by sorting in Java,
because Java's `UUID.compareTo` uses signed comparison and orders differently from Postgres.

`@Version` is kept as a second line of defence against any code path that bypasses the lock.

## Consequences
- ➕ No retry loops in the happy path; correctness is easy to explain.
- ➖ Throughput on a single hot account is bounded by lock hold time, so transactions must stay short
  (no remote calls inside them; that's why Kafka publishing will use the outbox pattern in Phase 2).
