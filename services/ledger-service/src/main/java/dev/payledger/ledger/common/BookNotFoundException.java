package dev.payledger.ledger.common;

import org.springframework.http.HttpStatus;

public class BookNotFoundException extends LedgerException {

    public BookNotFoundException(String bookRef) {
        super(HttpStatus.NOT_FOUND, "book-not-found", "no book " + bookRef);
    }
}
