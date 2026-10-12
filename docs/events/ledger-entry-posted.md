# Event: `ledger.entry.posted` (schema version 1)

Published by **ledger-service** once for every committed journal entry (deposit, transfer...), via the transactional outbox
([ADR 0004](../adr/0004-transactional-outbox.md)).

| | |
|---|---|
| Topic | `ledger.entries.v1` (configurable: `LEDGER_EVENTS_TOPIC`) |
| Key | journal entry id (UUID string) |
| Value | JSON, UTF-8 |
| Headers | `eventId` (UUID), `eventType` (`ledger.entry.posted`) |
| Delivery | at-least-once: de-duplicate on `eventId` |
| Ordering | per key (per entry) only |

```json
{
  "eventId": "0199d4c1-7e0a-7c55-9a1e-3f1b0b6a4d21",
  "eventType": "ledger.entry.posted",
  "schemaVersion": 1,
  "occurredAt": "2026-10-12T17:28:39.412048Z",
  "entryId": "01a13128-caf4-7667-8057-062fc17d4852",
  "entryType": "TRANSFER",
  "currency": "USD",
  "description": "dinner",
  "postings": [
    { "accountId": "01a13128-c966-…", "ownerId": "alice", "accountType": "USER", "amount": -2500, "balanceAfter": 7500 },
    { "accountId": "01a13128-c9e0-…", "ownerId": "bob",   "accountType": "USER", "amount":  2500, "balanceAfter": 2500 }
  ]
}
```

| Field | Notes |
|---|---|
| `entryType` | `DEPOSIT`, `TRANSFER`, `WITHDRAWAL`, `REVERSAL` |
| `amount`, `balanceAfter` | signed `long`, minor units (cents). Positive = money in. Postings always sum to 0. |
| `accountType` | `USER` = customer wallet, `SYSTEM` = internal account (e.g. external funding). Most consumers skip `SYSTEM`. |
| `ownerId` | included so consumers never need to call the ledger back |

## Evolution rules
- **Adding** a field is backward compatible. Consumers must ignore unknown fields ("tolerant reader").
- **Renaming, removing or changing the meaning** of a field needs `schemaVersion: 2` on a **new topic** (`ledger.entries.v2`),
  published alongside v1 until every consumer has moved.
- Consumers send events with an unknown `schemaVersion` to their dead-letter topic instead of guessing.
