package dev.payledger.ledger.common;

import org.springframework.http.HttpStatus;

public class EntryNotFoundException extends LedgerException {

    public EntryNotFoundException(String idempotencyKey) {
        super(HttpStatus.NOT_FOUND, "entry-not-found", "no journal entry with idempotency key " + idempotencyKey);
    }
}
