# ADR 0003: Idempotency keys on every money-moving endpoint

**Status:** Accepted

## Context
Networks fail after the server has committed but before the client receives the response.
The client retries. Without protection, the customer is charged twice.

## Decision
- `POST /deposits` and `POST /transfers` **require** an `Idempotency-Key` header (same model as Stripe).
- The key is stored on the journal entry with a `UNIQUE` constraint, in the **same transaction** as the money movement.
  Either both are committed or neither is, so there is no "processed but key not saved" window.
- A SHA-256 **fingerprint** of the request payload is stored too. Same key + same payload → return the original
  result (`200`, `Idempotent-Replayed: true`). Same key + different payload → `422 idempotency-key-reused`.
- **Concurrent duplicates**: both requests may pass the "already exists?" read. The loser's INSERT fails on the
  unique constraint, its transaction rolls back (so no money moves), and the service re-reads and returns the winner's result.
  This is covered by a 20-thread test.

## Why not Redis for idempotency?
A Redis key and a Postgres commit can't be atomic: a crash between them leaves them disagreeing.
Redis is still useful in front of this check as a cheap fast path or for rate limiting (Phase 3), but the database stays the source of truth.

## Consequences
- ➖ Keys are global, not scoped per client. When API clients/merchants are introduced, the constraint becomes `UNIQUE (client_id, idempotency_key)`.
- ➖ Keys never expire yet. Production systems expire them after ~24h–7d.
