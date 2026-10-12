package dev.payledger.core.user;

import dev.payledger.core.common.ApiException;
import org.springframework.http.HttpStatus;

class EmailInUseException extends ApiException {

    EmailInUseException() {
        super(HttpStatus.CONFLICT, "email-in-use", "this email address is linked to another PayLedger account");
    }
}
