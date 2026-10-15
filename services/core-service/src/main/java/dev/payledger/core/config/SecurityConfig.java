package dev.payledger.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * core-service is a stateless resource server: every API call carries a Keycloak access token,
 * relayed by the gateway. Browsers never talk to this service directly, so there are no
 * cookies here and CSRF protection lives in the gateway.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // The Email Worker's door: no user, its own shared secret (CaptureController).
                        .requestMatchers("/inbound/**").permitAll()
                        // Guests without an account: their own session cookie, checked in GuestController.
                        .requestMatchers("/api/guest/**").permitAll()
                        // Health probes (Docker, Kubernetes) on the management port; metrics stay closed.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .build();
    }
}
