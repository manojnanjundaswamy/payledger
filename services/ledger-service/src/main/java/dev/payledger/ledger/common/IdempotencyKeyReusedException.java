package dev.payledger.ledger.common;

import org.springframework.http.HttpStatus;

public class IdempotencyKeyReusedException extends LedgerException {

    public IdempotencyKeyReusedException(String key) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused", "Idempotency-Key " + key + " was already used with a different request body");
    }
}
