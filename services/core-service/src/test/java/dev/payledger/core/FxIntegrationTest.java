package dev.payledger.core;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.closeTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exchange rates: fetched once and kept, Gulf currencies added from their dollar peg, and a stale copy if the source fails. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "payledger.fx.refresh=0s")
@AutoConfigureMockMvc
class FxIntegrationTest {

    static final AtomicBoolean DOWN = new AtomicBoolean();
    static final AtomicInteger CALLS = new AtomicInteger();
    static final HttpServer SOURCE = start();

    @DynamicPropertySource
    static void source(DynamicPropertyRegistry registry) {
        registry.add("payledger.fx.url", () -> "http://localhost:" + SOURCE.getAddress().getPort());
    }

    @Autowired MockMvc mvc;

    @Test
    void ratesComeWithTheGulfCurrenciesAndSurviveTheSourceGoingDown() throws Exception {
        var me = jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("email", UUID.randomUUID() + "@example.com").claim("email_verified", true));
        mvc.perform(get("/api/core/fx").with(me)).andExpect(status().isOk())
                .andExpect(jsonPath("$.base").value("INR")).andExpect(jsonPath("$.date").value("2026-10-13"))
                .andExpect(jsonPath("$.rates.USD").value(0.012))
                // 0.012 USD per rupee x 3.6725 AED per USD.
                .andExpect(jsonPath("$.rates.AED").value(closeTo(0.04407, 0.00001)));
        DOWN.set(true);
        // The source is down: the last good rates are still answered.
        mvc.perform(get("/api/core/fx").with(me)).andExpect(status().isOk()).andExpect(jsonPath("$.rates.USD").value(0.012));
        DOWN.set(false);
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/latest", exchange -> {
                CALLS.incrementAndGet();
                byte[] body = "{\"amount\":1.0,\"base\":\"INR\",\"date\":\"2026-10-13\",\"rates\":{\"USD\":0.012,\"EUR\":0.0103}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                if (DOWN.get()) {
                    exchange.sendResponseHeaders(500, -1);
                } else {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
