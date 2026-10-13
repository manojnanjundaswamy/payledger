package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Balances and totals come from the ledger; here a fake ledger answers, so every case is deterministic. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BalanceIntegrationTest {

    /** Path (with query) -> JSON body. Anything else: 404, like a book the ledger has never seen. */
    static final Map<String, String> LEDGER_RESPONSES = new ConcurrentHashMap<>();
    static final AtomicBoolean LEDGER_DOWN = new AtomicBoolean();
    static final HttpServer LEDGER = startFakeLedger();

    @DynamicPropertySource
    static void ledgerUrl(DynamicPropertyRegistry registry) {
        registry.add("payledger.ledger.url", () -> "http://localhost:" + LEDGER.getAddress().getPort());
    }

    @AfterAll
    static void stopFakeLedger() {
        LEDGER.stop(0);
    }

    @Autowired MockMvc mvc;

    RequestPostProcessor me;
    String space;

    @BeforeEach
    void newUser() throws Exception {
        LEDGER_DOWN.set(false);
        String subject = UUID.randomUUID().toString();
        me = jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
        space = JsonPath.read(mvc.perform(get("/api/core/me").with(me)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
    }

    @Test
    void accountBalancesAreShownInTheUsersTerms() throws Exception {
        String bank = createAccount("BANK", "HDFC");
        String card = createAccount("CARD", "ICICI");
        String cash = createAccount("CASH", "Cash");
        LEDGER_RESPONSES.put("/api/v2/books/space:" + space + "/balances", """
                [{"accountRef":"account:%s","accountType":"ASSET","currency":"INR","balance":4951400},
                 {"accountRef":"account:%s","accountType":"LIABILITY","currency":"INR","balance":-48600},
                 {"accountRef":"category:x","accountType":"EXPENSE","currency":"INR","balance":48600},
                 {"accountRef":"equity:opening-balances","accountType":"EQUITY","currency":"INR","balance":-5000000}]
                """.formatted(bank, card));

        mvc.perform(get(path("/accounts")).with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].balance".formatted(bank)).value(4_951_400))
                // The card owes ₹486: shown as a positive amount owed.
                .andExpect(jsonPath("$[?(@.id == '%s')].balance".formatted(card)).value(48_600))
                // Nothing posted yet: zero, not unknown.
                .andExpect(jsonPath("$[?(@.id == '%s')].balance".formatted(cash)).value(0));
        mvc.perform(get(path("/accounts/" + card)).with(me)).andExpect(jsonPath("$.balance").value(48_600));
    }

    @Test
    void aSpaceWithNothingInTheLedgerYetHasZeroBalances() throws Exception {
        createAccount("BANK", "HDFC");
        mvc.perform(get(path("/accounts")).with(me)).andExpect(jsonPath("$[0].balance").value(0));
    }

    @Test
    void whenTheLedgerIsDownAccountsStillLoadWithoutBalances() throws Exception {
        createAccount("BANK", "HDFC");
        LEDGER_DOWN.set(true);
        mvc.perform(get(path("/accounts")).with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("HDFC"))
                .andExpect(jsonPath("$[0].balance").doesNotExist());
        mvc.perform(get(path("/category-totals")).with(me).param("from", "2026-10-01").param("to", "2026-10-31"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ledger-unavailable"));
    }

    @Test
    void categoryTotalsArePositiveForSpendingAndIncome() throws Exception {
        String groceries = UUID.randomUUID().toString();
        String salary = UUID.randomUUID().toString();
        LEDGER_RESPONSES.put("/api/v2/books/space:%s/totals?type=EXPENSE&from=2026-10-01&to=2026-10-31&byMonth=false".formatted(space), """
                [{"accountRef":"category:%s","accountType":"EXPENSE","currency":"INR","periodStart":null,"total":48600}]
                """.formatted(groceries));
        LEDGER_RESPONSES.put("/api/v2/books/space:%s/totals?type=INCOME&from=2026-10-01&to=2026-10-31&byMonth=false".formatted(space), """
                [{"accountRef":"category:%s","accountType":"INCOME","currency":"INR","periodStart":null,"total":-12000000}]
                """.formatted(salary));

        mvc.perform(get(path("/category-totals")).with(me).param("from", "2026-10-01").param("to", "2026-10-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].categoryId").value(groceries))
                .andExpect(jsonPath("$[0].amount").value(48_600));
        mvc.perform(get(path("/category-totals")).with(me).param("kind", "INCOME").param("from", "2026-10-01").param("to", "2026-10-31"))
                .andExpect(jsonPath("$[0].categoryId").value(salary))
                .andExpect(jsonPath("$[0].amount").value(12_000_000));
        mvc.perform(get(path("/category-totals")).with(me).param("kind", "EQUITY").param("from", "2026-10-01").param("to", "2026-10-31"))
                .andExpect(status().isBadRequest());
    }

    private String path(String rest) {
        return "/api/core/spaces/" + space + rest;
    }

    private String createAccount(String kind, String name) throws Exception {
        return JsonPath.read(mvc.perform(post(path("/accounts")).with(me).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"%s\",\"name\":\"%s\"}".formatted(kind, name)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static HttpServer startFakeLedger() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                // Decoded, as Spring decodes path variables: the client may send "space%3A..." for "space:...".
                String query = exchange.getRequestURI().getQuery();
                String uri = exchange.getRequestURI().getPath() + (query == null ? "" : "?" + query);
                String body = LEDGER_RESPONSES.get(uri);
                int status = LEDGER_DOWN.get() ? 500 : body == null ? 404 : 200;
                byte[] bytes = (body == null ? "{\"code\":\"book-not-found\"}" : body).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
