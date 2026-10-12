# Event: `ledger.entry.posted` (schema version 2)

Published by **ledger-service** once for every committed journal entry, reversals included, via the transactional outbox
([ADR 0004](../adr/0004-transactional-outbox.md)). The model behind it is in [ADR 0005](../adr/0005-ledger-v2-books.md).

| | |
|---|---|
| Topic | `ledger.entries.v2` (configurable: `LEDGER_EVENTS_TOPIC`) |
| Key | ledger book id (UUID string): one book's events stay in order |
| Value | JSON, UTF-8 |
| Headers | `eventId` (UUID), `eventType` (`ledger.entry.posted`) |
| Delivery | at-least-once: de-duplicate on `eventId` |
| Ordering | per book |

```json
{
  "eventId": "0199d4c1-7e0a-7c55-9a1e-3f1b0b6a4d21",
  "eventType": "ledger.entry.posted",
  "schemaVersion": 2,
  "occurredAt": "2026-10-12T18:10:39.412048Z",
  "entryId": "01a13128-caf4-7667-8057-062fc17d4852",
  "idempotencyKey": "txn:5f0c…:v1",
  "bookRef": "space:8b1e…",
  "bookKind": "PERSONAL",
  "entryType": "EXPENSE",
  "effectiveDate": "2026-10-05",
  "description": "Dinner",
  "sourceRef": "txn:5f0c…",
  "reversesEntryId": null,
  "postings": [
    { "accountRef": "category:eating-out", "accountType": "EXPENSE",   "currency": "INR", "amount":  250000, "balanceAfter":  250000 },
    { "accountRef": "card:icici",          "accountType": "LIABILITY", "currency": "INR", "amount": -250000, "balanceAfter": -250000 }
  ]
}
```

| Field | Notes |
|---|---|
| `bookKind` | `PERSONAL`, `HOUSEHOLD` or `GROUP` |
| `entryType` | the caller's label (`EXPENSE`, `INCOME`, `TRANSFER`, `SPLIT`, `SETTLEMENT`…); `REVERSAL` for reversals |
| `effectiveDate` | business date the money moved; reversals carry the original's date |
| `amount`, `balanceAfter` | signed `long`, minor units (paise). **Positive = debit.** Postings sum to 0 per currency. |
| `reversesEntryId` | set on reversals: the entry being cancelled |

## Evolution rules
- **Adding** a field is backward compatible. Consumers must ignore unknown fields ("tolerant reader").
- **Renaming, removing or changing the meaning** of a field needs a new `schemaVersion` on a **new topic**,
  published alongside the old one until every consumer has moved.
- Consumers send events with an unknown `schemaVersion` to their dead-letter topic instead of guessing.

## History
- **v1** (`ledger.entries.v1`, wallet era, retired): entries keyed by entry id, legs with `ownerId` and `USER`/`SYSTEM`
  account types. Only the parked notification-service still subscribes to it.
