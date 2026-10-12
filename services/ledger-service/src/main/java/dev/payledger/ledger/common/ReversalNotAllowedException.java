package dev.payledger.ledger.common;

import org.springframework.http.HttpStatus;

public class ReversalNotAllowedException extends LedgerException {

    public ReversalNotAllowedException(String message) {
        super(HttpStatus.CONFLICT, "reversal-not-allowed", message);
    }
}
