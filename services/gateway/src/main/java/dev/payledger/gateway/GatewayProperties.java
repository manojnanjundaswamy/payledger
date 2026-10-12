package dev.payledger.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param frontendUrl       where the web app lives; users land here after login and logout
 * @param coreServiceUrl    core-service, reachable from the gateway only
 * @param keycloakPublicUrl Keycloak as the BROWSER sees it (login and logout pages)
 * @param realm             Keycloak realm
 */
@ConfigurationProperties("payledger")
public record GatewayProperties(String frontendUrl, String coreServiceUrl, String keycloakPublicUrl, String realm) {

    String keycloakRealmUrl() {
        return keycloakPublicUrl + "/realms/" + realm;
    }
}
