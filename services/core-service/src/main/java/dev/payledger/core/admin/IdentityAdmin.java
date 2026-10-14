package dev.payledger.core.admin;

import dev.payledger.core.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * What the admin console asks of the identity provider (Keycloak): accounts, sign-in and the emails that go
 * with them. Staff never set or see a password: a new user gets an email to set their own.
 */
public interface IdentityAdmin {

    /** Creates the sign-in account and returns its subject (Keycloak user id). 409 email-in-use if it exists. */
    String create(String email, String displayName);

    /** Emails the user a link to verify their address and choose a password (a new account). */
    void sendWelcome(String subject);

    /** Emails the user a link to choose a new password. */
    void sendPasswordReset(String subject);

    /** Emails the user a link to verify their address. */
    void sendVerification(String subject);

    /** Allows or blocks signing in. Blocking also ends every session. */
    void setEnabled(String subject, boolean enabled);

    /** Ends every session: the user signs in again everywhere. */
    void signOut(String subject);

    /** Removes the sign-in account for good (the user deleted their account). */
    void delete(String subject);

    class IdentityUnavailableException extends ApiException {
        IdentityUnavailableException(String why) {
            super(HttpStatus.SERVICE_UNAVAILABLE, "identity-unavailable", "the sign-in service can't do this right now: " + why);
        }
    }
}
