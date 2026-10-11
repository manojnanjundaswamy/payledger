package dev.payledger.notification.events;

/** The record can never be processed, however often it is retried: send it straight to the dead-letter topic. */
public class UnprocessableEventException extends RuntimeException {

    public UnprocessableEventException(String message) {
        super(message);
    }

    public UnprocessableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
