package dev.payledger.core.common;

import org.springframework.http.HttpStatus;

/** Base for business-rule failures; each one maps to an RFC 9457 problem response with a stable code. */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }

    public String code() { return code; }
}
