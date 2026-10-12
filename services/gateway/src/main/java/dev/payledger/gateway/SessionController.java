package dev.payledger.gateway;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The first call the web app makes: am I signed in, and as whom? Always 200, so the app can
 * render either the dashboard or the landing page. Also issues the XSRF-TOKEN cookie.
 */
@RestController
class SessionController {

    @GetMapping("/api/session")
    Session session(@AuthenticationPrincipal OidcUser user, CsrfToken csrf) {
        csrf.getToken(); // tokens are deferred; reading it writes the XSRF-TOKEN cookie
        if (user == null) {
            return new Session(false, null, null, "/oauth2/authorization/keycloak", "/oauth2/authorization/keycloak?signup");
        }
        return new Session(true, user.getEmail(), user.getFullName(), null, null);
    }

    record Session(boolean authenticated, String email, String name, String loginUrl, String signupUrl) {
    }
}
