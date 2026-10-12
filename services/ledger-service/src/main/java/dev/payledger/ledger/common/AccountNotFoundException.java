package dev.payledger.ledger.common;

import org.springframework.http.HttpStatus;

public class AccountNotFoundException extends LedgerException {

    public AccountNotFoundException(String bookRef, String accountRef, String currency) {
        super(HttpStatus.NOT_FOUND, "account-not-found",
                "book " + bookRef + " has no " + currency + " account " + accountRef);
    }
}
