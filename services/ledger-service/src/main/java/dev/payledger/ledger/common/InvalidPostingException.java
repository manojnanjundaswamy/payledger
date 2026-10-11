package dev.payledger.ledger.common;

import org.springframework.http.HttpStatus;

public class InvalidPostingException extends LedgerException {

    public InvalidPostingException(String message) {
        super(HttpStatus.BAD_REQUEST, "invalid-posting", message);
    }
}
