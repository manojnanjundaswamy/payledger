package dev.payledger.ledger.common;

import org.springframework.http.HttpStatus;

public class UnsupportedCurrencyException extends LedgerException {

    public UnsupportedCurrencyException(String currency) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "unsupported-currency", "currency " + currency + " is not supported");
    }
}
