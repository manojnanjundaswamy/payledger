# Phase 1 study guide: ledger core

Goal: you can explain every line of `ledger-service` **out loud, without notes**.
Practice by answering each question below aloud (record yourself) in under 2 minutes.

## 1. Read the code in this order
1. `src/main/resources/db/migration/V1__ledger_schema.sql`: the rules live here
2. `journal/LedgerService.java`: the only writer
3. `account/Account.java#apply` and `AccountRepository#lockAllByIdsOrdered`
4. `movement/MoneyMovementService.java`: business operation → postings
5. `LedgerIntegrationTest.java`: the proof

## 2. Exercises (do these yourself; don't ask AI for the code)
1. **Break it on purpose.** Remove `@Lock` from `lockAllByIdsOrdered` and run the concurrency test. Watch it fail, then explain *why* (lost update).
2. **Cause a deadlock.** Replace the single ordered lock with two separate `findById` + lock calls in from→to order, and run 16 threads doing A→B and B→A. Read the Postgres deadlock error.
3. **Implement withdrawals** (`POST /api/v1/withdrawals`): debit user, credit the funding account. Add tests.
4. **Implement reversals**: `POST /api/v1/journal-entries/{id}/reversal` creates a new entry with negated postings. Ensure an entry can be reversed only once. (Hint: a unique constraint on `reverses_entry_id`.)
5. **Add a reconciliation endpoint** that checks `SUM(amount) over all postings = 0` and that each account's cached balance = sum of its postings.

## 3. Interview questions this project prepares you for
**Q: Why not store money as `double`?**
0.1 + 0.2 ≠ 0.3 in binary floating point. Use integer minor units (or `BigDecimal` with fixed scale). Bonus: mention `Math.addExact` for overflow.

**Q: Two requests withdraw from the same account at the same time. What happens?**
Explain the lost-update anomaly under READ COMMITTED, then your fix: `SELECT … FOR UPDATE`, which makes the second transaction wait. Compare with optimistic locking and SERIALIZABLE (see ADR 0002).

**Q: How do you avoid deadlocks?**
Acquire locks in a global consistent order. Mention that you order in SQL because Java's UUID ordering differs from Postgres's.

**Q: Client times out and retries. How do you avoid double charging?**
Idempotency key + unique constraint in the same transaction + payload fingerprint. Walk through the concurrent-duplicate race and why the rollback makes it safe (ADR 0003).

**Q: Why enforce rules in the database too? Isn't that duplication?**
Defence in depth: application code changes every week, constraints don't. A deferred constraint trigger checks balance at COMMIT, after all rows are inserted.

**Q: Offset vs keyset pagination?**
`OFFSET 100000` scans and discards 100k rows, and rows inserted while paging shift the pages. Keyset (`WHERE id < :cursor ORDER BY id DESC LIMIT n`) uses the index directly and is stable.

**Q: Why UUIDv7 instead of UUIDv4?**
v4 is random, so inserts land on random B-tree pages (page splits, poor cache locality). v7 is time-ordered, so inserts are close to append-only, like a sequence, yet still globally unique.

**Q: Why `open-in-view=false`?**
With it on, lazy-loading SQL can run during JSON serialization, outside your transaction and service layer. That hides N+1 queries and holds DB connections longer.

**Q: Why virtual threads, and any risk?**
Blocking JDBC calls no longer pin an OS thread, so throughput scales cheaply. Risk: the DB connection pool becomes the real limit. Thousands of virtual threads still share ~10 Hikari connections, so size the pool deliberately.

**Q: What would you change at 100× scale?**
Hot-account sharding, read replicas for statements, partitioning `postings` by time, moving notifications and analytics to Kafka consumers (Phase 2), and archiving old postings.

## 4. Concepts to study alongside (in this order)
- Postgres transaction isolation levels and MVCC (Postgres docs, chapter "Concurrency Control")
- *Designing Data-Intensive Applications*, chapter 7 (Transactions)
- Stripe engineering blog: "Designing robust and predictable APIs with idempotency"
- RFC 9457 (Problem Details for HTTP APIs)
