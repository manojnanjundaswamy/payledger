# Commands: `ledger.post` and `ledger.reverse` (schema version 1)

Sent by **core-service** whenever a user's change moves money: an account's opening balance, or adding, editing
or deleting a transaction. The command is written to core-service's outbox **in the same database transaction as
the change** ([ADR 0004](../adr/0004-transactional-outbox.md)) and relayed to Kafka. **ledger-service** consumes it and
calls the same code as its `POST /api/v2/entries` and `POST /api/v2/reversals` endpoints ([ADR 0005](../adr/0005-ledger-v2-books.md)).

| | |
|---|---|
| Topic | `ledger.commands.v1` (configurable: `LEDGER_COMMANDS_TOPIC`; created by `kafka-init` and by core-service) |
| Key | the space id (UUID string). One space = one ledger book, so its commands stay in order: a reversal is never processed before the entry it reverses |
| Value | JSON, UTF-8 |
| Headers | `eventId` (= `commandId`), `eventType` (`ledger.post` or `ledger.reverse`) |
| Delivery | at-least-once. The consumer relies on the ledger's **idempotency keys**: the same command twice posts once |

## `ledger.post`

```json
{
  "commandId": "01a13360-c440-7988-b523-3f4a69fe9745",
  "commandType": "ledger.post",
  "schemaVersion": 1,
  "createdAt": "2026-10-13T04:12:09.512Z",
  "idempotencyKey": "txn:01a13361-…:v1",
  "bookRef": "space:01a1319e-8acf-…",
  "bookKind": "PERSONAL",
  "entryType": "EXPENSE",
  "effectiveDate": "2026-10-05",
  "description": "BigBasket",
  "sourceRef": "txn:01a13361-…",
  "legs": [
    { "accountRef": "category:01a1…", "accountType": "EXPENSE",   "currency": "INR", "amount":  48600 },
    { "accountRef": "account:01a1…",  "accountType": "LIABILITY", "currency": "INR", "amount": -48600 }
  ]
}
```

## `ledger.reverse`

```json
{
  "commandId": "01a13362-…",
  "commandType": "ledger.reverse",
  "schemaVersion": 1,
  "createdAt": "2026-10-13T04:15:40.003Z",
  "idempotencyKey": "txn:01a13361-…:v1:reversal",
  "bookRef": "space:01a1319e-8acf-…",
  "reversesIdempotencyKey": "txn:01a13361-…:v1",
  "description": "Edited: BigBasket"
}
```

## How core-service builds them

| User action | Commands |
|---|---|
| Add account with opening balance X | post `account:<id>:opening:v1`: account ±X, `equity:opening-balances` ∓X |
| Change the opening balance | reverse `…:opening:v<n>`, post `…:opening:v<n+1>` (a zero balance posts nothing) |
| Add a transaction | post `txn:<id>:v1` |
| Edit amount, account, category, date or kind | reverse the live version, post `txn:<id>:v<new>` |
| Edit only description, note or tags | nothing (the live ledger entry stays) |
| Delete | reverse the live version |

Legs (amounts are paise, positive = debit; a card is a `LIABILITY`, everything else an `ASSET`):

| Kind | Legs |
|---|---|
| Expense A | `category` **+A** (EXPENSE), `account` **−A** |
| Income A | `account` **+A**, `category` **−A** (INCOME) |
| Transfer A from → to | `to` **+A**, `from` **−A** (paying a card bill reduces what you owe) |

| Ref | Ledger type |
|---|---|
| `account:<uuid>` | `ASSET` (bank, cash, wallet) or `LIABILITY` (card) |
| `category:<uuid>` | `EXPENSE` or `INCOME` |
| `equity:opening-balances` | `EQUITY` |

## Rules for the consumer
- Apply commands **in partition order** and only commit the offset after the ledger transaction committed.
- A replayed idempotency key is success, not an error.
- A command the ledger rejects (it would only happen through a bug, since core-service validates first) goes to a
  dead-letter topic and an alert; it must not block later commands of other spaces.
