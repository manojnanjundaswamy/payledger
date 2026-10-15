package dev.payledger.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param frontendUrl       where the web app lives; users land here after login and logout
 * @param coreServiceUrl    core-service, reachable from the gateway only
 * @param keycloakPublicUrl Keycloak as the BROWSER sees it (login and logout pages)
 * @param realm             Keycloak realm
 */
@ConfigurationProperties("payledger")
public record GatewayProperties(String frontendUrl, String coreServiceUrl, String keycloakPublicUrl, String realm, String googleClientId) {

    /** "Continue with Google" is offered when Keycloak has Google set up (GOOGLE_CLIENT_ID in .env, keycloak-sync). */
    boolean google() {
        return googleClientId != null && !googleClientId.isBlank();
    }

    String keycloakRealmUrl() {
        return keycloakPublicUrl + "/realms/" + realm;
    }
}
