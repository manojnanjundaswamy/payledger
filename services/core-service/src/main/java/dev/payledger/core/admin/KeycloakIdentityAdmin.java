package dev.payledger.core.admin;

import dev.payledger.core.user.EmailInUseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@link IdentityAdmin} over Keycloak's admin REST API, as the confidential client {@code payledger-core-admin}
 * (client credentials; its service account may only manage users of the payledger realm).
 * Without a client secret configured every call answers 503 identity-unavailable, and the rest of the console works.
 */
@Component
@EnableConfigurationProperties(KeycloakIdentityAdmin.Properties.class)
class KeycloakIdentityAdmin implements IdentityAdmin {

    private static final Logger log = LoggerFactory.getLogger(KeycloakIdentityAdmin.class);
    /** How long the links in welcome and reset emails work. */
    private static final Duration LINK_LIFESPAN = Duration.ofDays(3);

    /**
     * @param url          Keycloak's base URL on the internal network
     * @param clientSecret the payledger-core-admin client's secret; blank = not configured
     */
    @ConfigurationProperties("payledger.identity")
    record Properties(String url, String realm, String clientId, String clientSecret, Duration timeout) {
    }

    private record Token(String value, Instant expires) {
    }

    private final RestClient http;
    private final Properties props;
    private volatile Token token;

    KeycloakIdentityAdmin(RestClient.Builder builder, Properties props) {
        this.props = props;
        var settings = HttpClientSettings.defaults().withTimeouts(props.timeout(), props.timeout());
        this.http = builder.baseUrl(props.url())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }

    @Override
    public String create(String email, String displayName) {
        return call("create the account", () -> {
            try {
                var response = http.post().uri("/admin/realms/{realm}/users", props.realm())
                        .headers(h -> h.setBearerAuth(token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("username", email, "email", email, "firstName", displayName,
                                "enabled", true, "emailVerified", false))
                        .retrieve().toBodilessEntity();
                URI location = response.getHeaders().getLocation();
                if (location == null) {
                    throw new IdentityUnavailableException("no id for the new account");
                }
                String path = location.getPath();
                return path.substring(path.lastIndexOf('/') + 1);
            } catch (HttpClientErrorException.Conflict e) {
                throw new EmailInUseException();
            }
        });
    }

    @Override
    public void sendWelcome(String subject) {
        executeActions(subject, List.of("VERIFY_EMAIL", "UPDATE_PASSWORD"));
    }

    @Override
    public void sendPasswordReset(String subject) {
        executeActions(subject, List.of("UPDATE_PASSWORD"));
    }

    @Override
    public void sendVerification(String subject) {
        run("send the email", () -> http.put().uri("/admin/realms/{realm}/users/{id}/send-verify-email", props.realm(), subject)
                .headers(h -> h.setBearerAuth(token())).retrieve().toBodilessEntity());
    }

    @Override
    public void setEnabled(String subject, boolean enabled) {
        run(enabled ? "enable sign-in" : "block sign-in", () -> http.put().uri("/admin/realms/{realm}/users/{id}", props.realm(), subject)
                .headers(h -> h.setBearerAuth(token()))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("enabled", enabled))
                .retrieve().toBodilessEntity());
        if (!enabled) {
            signOut(subject);
        }
    }

    @Override
    public void signOut(String subject) {
        run("sign out", () -> http.post().uri("/admin/realms/{realm}/users/{id}/logout", props.realm(), subject)
                .headers(h -> h.setBearerAuth(token())).retrieve().toBodilessEntity());
    }

    @Override
    public void delete(String subject) {
        run("delete the account", () -> http.delete().uri("/admin/realms/{realm}/users/{id}", props.realm(), subject)
                .headers(h -> h.setBearerAuth(token())).retrieve().toBodilessEntity());
    }

    private void executeActions(String subject, List<String> actions) {
        run("send the email", () -> http.put()
                .uri(u -> u.path("/admin/realms/{realm}/users/{id}/execute-actions-email")
                        .queryParam("lifespan", LINK_LIFESPAN.toSeconds()).build(props.realm(), subject))
                .headers(h -> h.setBearerAuth(token()))
                .contentType(MediaType.APPLICATION_JSON).body(actions)
                .retrieve().toBodilessEntity());
    }

    /** A service-account token, reused until a minute before it expires. */
    private String token() {
        Token current = token;
        if (current != null && current.expires().isAfter(Instant.now().plusSeconds(60))) {
            return current.value();
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", props.clientId());
        form.add("client_secret", props.clientSecret());
        Map<?, ?> body = http.post().uri("/realms/{realm}/protocol/openid-connect/token", props.realm())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().body(Map.class);
        if (body == null || !(body.get("access_token") instanceof String value)) {
            throw new IdentityUnavailableException("no token");
        }
        long seconds = body.get("expires_in") instanceof Number n ? n.longValue() : 60;
        token = new Token(value, Instant.now().plusSeconds(seconds));
        return value;
    }

    private <T> T call(String what, java.util.function.Supplier<T> action) {
        if (props.clientSecret() == null || props.clientSecret().isBlank()) {
            throw new IdentityUnavailableException("the admin client isn't configured");
        }
        try {
            return action.get();
        } catch (HttpClientErrorException.NotFound e) {
            throw new IdentityUnavailableException("the account doesn't exist in the sign-in service");
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                token = null;
            }
            log.warn("Keycloak refused to {}: {}", what, e.getStatusCode());
            throw new IdentityUnavailableException("couldn't " + what);
        } catch (RestClientException e) {
            log.warn("Keycloak unreachable trying to {}: {}", what, e.getMessage());
            throw new IdentityUnavailableException("couldn't " + what);
        }
    }

    private void run(String what, Runnable action) {
        call(what, () -> {
            action.run();
            return null;
        });
    }
}
