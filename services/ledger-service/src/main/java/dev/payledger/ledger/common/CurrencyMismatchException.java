package dev.payledger.ledger.common;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class CurrencyMismatchException extends LedgerException {

    public CurrencyMismatchException(UUID accountId, String accountCurrency, String requestCurrency) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "currency-mismatch", "account " + accountId + " holds " + accountCurrency + ", request is in " + requestCurrency);
    }
}
