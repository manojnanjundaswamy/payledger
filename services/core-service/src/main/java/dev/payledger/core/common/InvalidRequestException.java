package dev.payledger.core.common;

import org.springframework.http.HttpStatus;

/** A request that is well-formed but breaks a business rule, e.g. a transfer to the same account. */
public class InvalidRequestException extends ApiException {

    public InvalidRequestException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }
}
