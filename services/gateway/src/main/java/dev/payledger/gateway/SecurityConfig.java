package dev.payledger.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

/**
 * Backend-for-frontend security: the gateway is the OAuth2 client, the browser only ever gets
 * an httpOnly session cookie (sessions live in Redis). Tokens never reach JavaScript, so an XSS
 * bug can't steal them.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    static final String REGISTRATION_ID = "keycloak";

    @Bean
    SecurityFilterChain web(HttpSecurity http, ClientRegistrationRepository clients,
                            GatewayProperties props) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/session").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .requestMatchers("/oauth2/authorization/**", "/login/oauth2/code/**", "/logout").permitAll()
                        .anyRequest().denyAll())
                .oauth2Login(login -> login
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(authorizationRequests(clients)))
                        .defaultSuccessUrl(props.frontendUrl(), true)
                        .failureUrl(props.frontendUrl() + "?loginError"))
                .logout(logout -> logout.logoutSuccessHandler(keycloakLogout(props)))
                // SPA mode: the token is in a readable XSRF-TOKEN cookie, the app echoes it in X-XSRF-TOKEN.
                .csrf(csrf -> csrf.spa())
                // An API call without a session gets 401 (the app shows "sign in"), not a redirect to a login page.
                .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), pathPattern("/api/**")))
                .build();
    }

    /**
     * PKCE on every login (recommended even for confidential clients), and
     * {@code /oauth2/authorization/keycloak?signup} opens Keycloak's registration form directly.
     */
    static OAuth2AuthorizationRequestResolver authorizationRequests(ClientRegistrationRepository clients) {
        var defaults = new DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization");
        defaults.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                return withSignup(request, defaults.resolve(request));
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
                return withSignup(request, defaults.resolve(request, clientRegistrationId));
            }
        };
    }

    private static OAuth2AuthorizationRequest withSignup(HttpServletRequest request, OAuth2AuthorizationRequest authorization) {
        if (authorization == null || request.getParameter("signup") == null) {
            return authorization;
        }
        return OAuth2AuthorizationRequest.from(authorization)
                .additionalParameters(params -> params.put("prompt", "create"))
                .build();
    }

    /**
     * Ends the Keycloak session too (RP-initiated logout), otherwise the next "sign in" would
     * silently log the same person back in. Answers with JSON because the app calls it with fetch
     * and then navigates to the returned URL.
     */
    static LogoutSuccessHandler keycloakLogout(GatewayProperties props) {
        return (request, response, authentication) -> {
            var url = UriComponentsBuilder.fromUriString(props.keycloakRealmUrl() + "/protocol/openid-connect/logout")
                    .queryParam("client_id", "payledger-gateway")
                    .queryParam("post_logout_redirect_uri", props.frontendUrl());
            String idToken = idToken(authentication);
            if (idToken != null) {
                url.queryParam("id_token_hint", idToken);
            }
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"redirectUrl\":\"" + url.encode().build().toUriString() + "\"}");
        };
    }

    private static String idToken(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof OidcUser user
                ? user.getIdToken().getTokenValue()
                : null;
    }
}
