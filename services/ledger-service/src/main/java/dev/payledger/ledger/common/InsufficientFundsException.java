package dev.payledger.ledger.common;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class InsufficientFundsException extends LedgerException {

    public InsufficientFundsException(UUID accountId, long balance, long requested) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "insufficient-funds", "account " + accountId + " has balance " + balance + " but " + requested + " was requested");
    }
}
