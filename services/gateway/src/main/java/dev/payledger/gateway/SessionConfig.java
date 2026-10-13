package dev.payledger.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * The session cookie is the browser's only credential, so it is configured explicitly rather than
 * relying on defaults: HttpOnly (JavaScript can never read it), SameSite=Lax (sent on the redirect
 * back from Keycloak, not on cross-site POSTs) and Secure outside local development.
 */
@Configuration(proxyBeanMethods = false)
class SessionConfig {

    static final String COOKIE_NAME = "PAYLEDGER_SESSION";

    @Bean
    CookieSerializer sessionCookie(@Value("${payledger.cookie-secure}") boolean secure) {
        var cookie = new DefaultCookieSerializer();
        cookie.setCookieName(COOKIE_NAME);
        cookie.setUseHttpOnlyCookie(true);
        cookie.setSameSite("Lax");
        cookie.setUseSecureCookie(secure);
        cookie.setCookiePath("/");
        return cookie;
    }

    /**
     * The user's tokens live in the session (Redis), next to the login itself. Spring Boot's default
     * keeps them in this instance's memory: after a gateway restart or on a second instance the
     * session still said "signed in" but had no tokens, so every API call was redirected to Keycloak,
     * which the web app's fetch can't follow. The app broke until the user signed in again.
     */
    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }
}
