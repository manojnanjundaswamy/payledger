package dev.payledger.core.user;

import dev.payledger.core.common.ApiException;
import org.springframework.http.HttpStatus;

class EmailNotVerifiedException extends ApiException {

    EmailNotVerifiedException() {
        super(HttpStatus.FORBIDDEN, "email-not-verified", "verify your email address to use PayLedger");
    }
}
