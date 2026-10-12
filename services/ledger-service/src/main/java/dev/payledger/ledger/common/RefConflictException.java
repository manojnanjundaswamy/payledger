package dev.payledger.ledger.common;

import org.springframework.http.HttpStatus;

/** A reference is already in use with a different meaning, e.g. account "bank:1" exists as an ASSET and is now posted as EXPENSE. */
public class RefConflictException extends LedgerException {

    public RefConflictException(String message) {
        super(HttpStatus.CONFLICT, "ref-conflict", message);
    }
}
