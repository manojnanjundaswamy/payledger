package dev.payledger.core.user;

import dev.payledger.core.common.ApiException;
import org.springframework.http.HttpStatus;

class PreferencesTooLargeException extends ApiException {

    PreferencesTooLargeException(int limit) {
        super(HttpStatus.BAD_REQUEST, "preferences-too-large", "preferences must be at most " + limit + " bytes of JSON");
    }
}
