package dev.payledger.core.user;

import dev.payledger.core.common.ApiException;
import org.springframework.http.HttpStatus;

public class EmailInUseException extends ApiException {

    public EmailInUseException() {
        super(HttpStatus.CONFLICT, "email-in-use", "this email address is linked to another PayLedger account");
    }
}
