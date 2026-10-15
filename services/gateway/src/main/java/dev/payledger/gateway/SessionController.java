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

    private final GatewayProperties props;

    SessionController(GatewayProperties props) {
        this.props = props;
    }

    @GetMapping("/api/session")
    Session session(@AuthenticationPrincipal OidcUser user, CsrfToken csrf) {
        csrf.getToken(); // tokens are deferred; reading it writes the XSRF-TOKEN cookie
        if (user == null) {
            return new Session(false, null, null, "/oauth2/authorization/keycloak", "/oauth2/authorization/keycloak?signup", null,
                    props.google() ? "/oauth2/authorization/keycloak?idp=google" : null);
        }
        // Password, email and sign-in methods are managed in Keycloak's account console.
        return new Session(true, user.getEmail(), user.getFullName(), null, null, props.keycloakRealmUrl() + "/account", null);
    }

    /**
     * @param accountUrl where a signed-in user changes their password or email
     * @param googleUrl  "Continue with Google", when Google sign-in is set up
     */
    record Session(boolean authenticated, String email, String name, String loginUrl, String signupUrl, String accountUrl, String googleUrl) {
    }
}
