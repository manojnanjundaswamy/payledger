package dev.payledger.ledger.commands;

/** The command can never be applied, however often it is retried: send it straight to the dead-letter topic. */
public class UnprocessableCommandException extends RuntimeException {

    public UnprocessableCommandException(String message) {
        super(message);
    }

    public UnprocessableCommandException(String message, Throwable cause) {
        super(message, cause);
    }
}
