package dev.payledger.ledger.common;

import org.springframework.http.HttpStatus;

/** Base for business-rule failures; each one maps to an RFC 9457 problem response. */
public abstract class LedgerException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected LedgerException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }

    /** Stable, machine-readable error code clients can switch on (unlike the message). */
    public String code() { return code; }
}
