package dev.payledger.ledger.common;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class AccountNotFoundException extends LedgerException {

    public AccountNotFoundException(UUID accountId) {
        super(HttpStatus.NOT_FOUND, "account-not-found", "account " + accountId + " does not exist");
    }
}
