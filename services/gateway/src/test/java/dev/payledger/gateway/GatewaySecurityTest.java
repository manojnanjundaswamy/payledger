package dev.payledger.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
// A short read timeout so the "core-service hangs" test stays fast; production uses application.properties.
@SpringBootTest(properties = "spring.http.clients.read-timeout=1s")
@AutoConfigureMockMvc
class GatewaySecurityTest {

    /** Stands in for core-service and records what the gateway forwarded. */
    static final HttpServer CORE = startFakeCore();
    static final AtomicReference<Map<String, List<String>>> forwardedHeaders = new AtomicReference<>();

    @DynamicPropertySource
    static void coreServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("payledger.core-service-url", () -> "http://localhost:" + CORE.getAddress().getPort());
    }

    @AfterAll
    static void stopFakeCore() {
        CORE.stop(0);
    }

    @Autowired MockMvc mvc;

    @Test
    void anonymousVisitorGetsLoginLinksAndACsrfCookie() throws Exception {
        mvc.perform(get("/api/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.loginUrl").value("/oauth2/authorization/keycloak"))
                .andExpect(jsonPath("$.signupUrl").value("/oauth2/authorization/keycloak?signup"))
                .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void apiCallsWithoutASessionGet401NotARedirect() throws Exception {
        mvc.perform(get("/api/core/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginUsesPkceAndSignupOpensTheRegistrationForm() throws Exception {
        String login = redirect(mvc.perform(get("/oauth2/authorization/keycloak")).andReturn());
        assertThat(login).startsWith("http://localhost:8180/realms/payledger/protocol/openid-connect/auth?")
                .contains("client_id=payledger-gateway", "code_challenge_method=S256", "code_challenge=")
                .doesNotContain("prompt=create");

        String signup = redirect(mvc.perform(get("/oauth2/authorization/keycloak").param("signup", "")).andReturn());
        assertThat(signup).contains("prompt=create", "code_challenge_method=S256");

        // Straight to Google; an unknown provider name is ignored.
        String google = redirect(mvc.perform(get("/oauth2/authorization/keycloak").param("idp", "google")).andReturn());
        assertThat(google).contains("kc_idp_hint=google", "code_challenge_method=S256").doesNotContain("prompt=create");
        String other = redirect(mvc.perform(get("/oauth2/authorization/keycloak").param("idp", "evil")).andReturn());
        assertThat(other).doesNotContain("kc_idp_hint");
    }

    @Test
    void signedInCallsReachCoreServiceWithTheAccessTokenAndWithoutBrowserCookies() throws Exception {
        mvc.perform(get("/api/session").with(oidcLogin().oidcUser(user())))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.email").value("priya@example.com"))
                .andExpect(jsonPath("$.accountUrl").value("http://localhost:8180/realms/payledger/account"));

        mvc.perform(get("/api/core/me").with(oidcLogin().oidcUser(user()))
                        .cookie(new Cookie("PAYLEDGER_SESSION", "secret-session-id")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fake").value("core"));

        Map<String, List<String>> headers = forwardedHeaders.get();
        assertThat(headers.get("Authorization")).singleElement().asString().startsWith("Bearer ");
        assertThat(headers).doesNotContainKey("Cookie");
    }

    @Test
    void theEmailWorkersDoorNeedsNoSessionButOnlyTakesPostsToItsOnePath() throws Exception {
        mvc.perform(post("/inbound/email").header("X-Inbound-Secret", "s").content("raw mail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fake").value("core"));
        assertThat(forwardedHeaders.get()).doesNotContainKey("Authorization").containsKey("X-inbound-secret");
        mvc.perform(get("/inbound/email")).andExpect(status().is4xxClientError());
        mvc.perform(post("/inbound/other")).andExpect(status().is4xxClientError());
    }

    @Test
    void aSessionCoreServiceNoLongerAcceptsEndsSoTheAppAsksToSignInOnce() throws Exception {
        // A real (Redis) session, as a signed-in browser has.
        Cookie session = mvc.perform(get("/oauth2/authorization/keycloak")).andReturn().getResponse().getCookie("PAYLEDGER_SESSION");
        assertThat(session).isNotNull();
        List<String> cleared = mvc.perform(get("/api/core/rejected").with(oidcLogin().oidcUser(user())).cookie(session))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getHeaders("Set-Cookie");
        assertThat(cleared).anySatisfy(c -> assertThat(c).startsWith("PAYLEDGER_SESSION=").contains("Max-Age=0"));
    }

    @Test
    void loginStartsAnHttpOnlySameSiteSessionCookie() throws Exception {
        // Starting a login stores the PKCE verifier and state in the (Redis) session.
        List<String> setCookies = mvc.perform(get("/oauth2/authorization/keycloak")).andReturn().getResponse().getHeaders("Set-Cookie");

        assertThat(setCookies).filteredOn(c -> c.startsWith("PAYLEDGER_SESSION="))
                .singleElement().asString().contains("HttpOnly", "SameSite=Lax");
    }

    @Test
    void logoutNeedsTheCsrfTokenAndAlsoEndsTheKeycloakSession() throws Exception {
        mvc.perform(post("/logout").with(oidcLogin().oidcUser(user()))).andExpect(status().isForbidden());

        // The web app's flow: read the XSRF-TOKEN cookie, echo it in the X-XSRF-TOKEN header.
        Cookie xsrf = mvc.perform(get("/api/session")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        mvc.perform(post("/logout").with(oidcLogin().oidcUser(user())).cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirectUrl").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.startsWith("http://localhost:8180/realms/payledger/protocol/openid-connect/logout?"),
                        org.hamcrest.Matchers.containsString("post_logout_redirect_uri=http://localhost:5173"),
                        org.hamcrest.Matchers.containsString("id_token_hint="))));
    }

    @Test
    void aHangingCoreServiceGetsAnErrorQuicklyInsteadOfAHungPage() throws Exception {
        long start = System.nanoTime();
        mvc.perform(get("/api/core/slow").with(oidcLogin().oidcUser(user())))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("core-service-timeout"));
        long millis = (System.nanoTime() - start) / 1_000_000;

        // Without a read timeout the browser waited forever and the app never left its loading skeleton.
        assertThat(millis).isLessThan(2_500);
    }

    private static DefaultOidcUser user() {
        var idToken = new OidcIdToken("id-token-value", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("sub", "user-1", "email", "priya@example.com", "name", "Priya Sharma"));
        return new DefaultOidcUser(List.of(), idToken);
    }

    private static String redirect(MvcResult result) {
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        return result.getResponse().getHeader("Location");
    }

    private static HttpServer startFakeCore() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/inbound/email", exchange -> {
                forwardedHeaders.set(Map.copyOf(exchange.getRequestHeaders()));
                byte[] body = "{\"fake\":\"core\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/api/core/rejected", exchange -> {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
            });
            server.createContext("/api/core/me", exchange -> {
                forwardedHeaders.set(Map.copyOf(exchange.getRequestHeaders()));
                byte[] body = "{\"fake\":\"core\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/api/core/slow", exchange -> {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            // Several threads, so the slow handler can't hold up the other tests' requests.
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
