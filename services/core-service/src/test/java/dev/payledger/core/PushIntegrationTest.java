package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import dev.payledger.core.push.WebPushCrypto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Push end to end, against a stand-in push service: a device subscribes, a notification lands in the bell, and the
 * device receives it encrypted for it and signed by the server. Unknown hosts are refused; a gone device is forgotten.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PushIntegrationTest {

    record Received(String path, String authorization, String encoding, byte[] body) {
    }

    static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    static final AtomicInteger NEXT_STATUS = new AtomicInteger(201);
    static final HttpServer PUSH_SERVICE = start();

    @DynamicPropertySource
    static void pushHosts(DynamicPropertyRegistry registry) {
        registry.add("payledger.push.extra-hosts", () -> "localhost");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void aNotificationReachesTheDeviceEncryptedAndSigned() throws Exception {
        RequestPostProcessor me = user();
        String key = JsonPath.read(mvc.perform(get("/api/core/push/key").with(me)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.publicKey");

        // The browser: its own key pair and auth secret, as PushManager.subscribe makes them.
        KeyPair browser = WebPushCrypto.newKeyPair();
        byte[] p256dh = WebPushCrypto.raw((ECPublicKey) browser.getPublic());
        byte[] auth = new byte[16];
        new java.security.SecureRandom().nextBytes(auth);
        String endpoint = "http://localhost:" + PUSH_SERVICE.getAddress().getPort() + "/push/" + UUID.randomUUID();
        subscribe(me, endpoint, p256dh, auth).andExpect(status().isNoContent());
        mvc.perform(get("/api/core/push/key").with(me)).andExpect(jsonPath("$.devices").value(1));

        // Only real push services: an address the browser makes up is refused.
        subscribe(me, "https://evil.example.com/steal", p256dh, auth)
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-subscription"));
        subscribe(me, endpoint.replace("/push/", "/x/"), new byte[]{1, 2, 3}, auth).andExpect(jsonPath("$.code").value("invalid-subscription"));

        RECEIVED.clear();
        mvc.perform(post("/api/core/push/test").with(me)).andExpect(status().isAccepted());
        await().until(() -> !RECEIVED.isEmpty());
        Received r = RECEIVED.getFirst();
        assertThat(r.encoding()).isEqualTo("aes128gcm");
        assertThat(r.authorization()).startsWith("vapid t=").endsWith(", k=" + key);
        String message = new String(WebPushCrypto.decrypt(r.body(), (ECPrivateKey) browser.getPrivate(), p256dh, auth), StandardCharsets.UTF_8);
        assertThat(message).contains("\"title\":\"Notifications are on\"").contains("\"link\":\"/settings?tab=notifications\"");

        // A device that's gone (410) is forgotten.
        NEXT_STATUS.set(410);
        mvc.perform(post("/api/core/push/test").with(me));
        await().until(() -> jdbc.sql("select count(*) from push_subscriptions where endpoint = :e").param("e", endpoint).query(Long.class).single() == 0);
        NEXT_STATUS.set(201);
    }

    private org.springframework.test.web.servlet.ResultActions subscribe(RequestPostProcessor me, String endpoint, byte[] p256dh, byte[] auth) throws Exception {
        return mvc.perform(post("/api/core/push/subscriptions").with(me).contentType(MediaType.APPLICATION_JSON).content("""
                {"endpoint":"%s","keys":{"p256dh":"%s","auth":"%s"}}
                """.formatted(endpoint, WebPushCrypto.b64(p256dh), WebPushCrypto.b64(auth))));
    }

    private static RequestPostProcessor user() {
        String subject = UUID.randomUUID().toString();
        return jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/push/", exchange -> {
                RECEIVED.add(new Received(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("Content-Encoding"), exchange.getRequestBody().readAllBytes()));
                exchange.sendResponseHeaders(NEXT_STATUS.get(), -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
