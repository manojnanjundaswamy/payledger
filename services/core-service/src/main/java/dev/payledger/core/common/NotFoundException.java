package dev.payledger.core.common;

import org.springframework.http.HttpStatus;

/** Something inside a space that does not exist (or was deleted). Code: {@code <what>-not-found}. */
public class NotFoundException extends ApiException {

    public NotFoundException(String what) {
        super(HttpStatus.NOT_FOUND, what + "-not-found", "no such " + what.replace('-', ' '));
    }
}
