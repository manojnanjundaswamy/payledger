package dev.payledger.core.user;

import dev.payledger.core.common.ApiException;
import org.springframework.http.HttpStatus;

class AccountDisabledException extends ApiException {

    AccountDisabledException() {
        super(HttpStatus.FORBIDDEN, "account-disabled", "this account is disabled; contact PayLedger support");
    }
}
