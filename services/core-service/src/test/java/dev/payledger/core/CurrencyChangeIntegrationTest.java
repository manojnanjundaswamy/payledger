package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Changing currency: converted at a rate, or the same numbers relabelled; balances, entries and plans move together. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CurrencyChangeIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void convertingMovesEverythingAtTheRateAndRebooksTheLedger() throws Exception {
        RequestPostProcessor me = user();
        String space = JsonPath.read(mvc.perform(get("/api/core/me").with(me)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
        String base = "/api/core/spaces/" + space;
        String bank = id(post(base + "/accounts"), me, "{\"kind\":\"BANK\",\"name\":\"HDFC\",\"openingBalance\":10000000,\"openingDate\":\"2026-01-01\"}");
        // A dollar account kept on purpose stays as it is.
        String usd = id(post(base + "/accounts"), me, "{\"kind\":\"BANK\",\"name\":\"Chase\",\"currency\":\"USD\",\"openingBalance\":50000,\"openingDate\":\"2026-01-01\"}");
        String food = JsonPath.<List<String>>read(mvc.perform(get(base + "/categories").with(me)).andReturn().getResponse().getContentAsString(),
                "$[?(@.name == 'Groceries')].id").getFirst();
        String txn = id(post(base + "/transactions"), me, """
                {"kind":"EXPENSE","amount":100000,"accountId":"%s","categoryId":"%s","occurredOn":"2026-10-03","description":"BigBasket"}
                """.formatted(bank, food));
        ok(put(base + "/budgets/" + food), me, "{\"amount\":1200000}");

        // The preview: ₹1,00,000 at 0.012 is $1,200.00.
        mvc.perform(get(base + "/currency/preview").param("to", "USD").param("mode", "CONVERT").param("rate", "0.012").with(me))
                .andExpect(jsonPath("$.from").value("INR")).andExpect(jsonPath("$.accounts").value(1)).andExpect(jsonPath("$.entries").value(1))
                .andExpect(jsonPath("$.sampleBefore").value(10000000)).andExpect(jsonPath("$.sampleAfter").value(120000));

        ok(post(base + "/currency"), me, "{\"to\":\"USD\",\"mode\":\"CONVERT\",\"rate\":0.012}");
        record Acc(String currency, long openingBalance) {
        }
        assertThat(jdbc.sql("select currency, opening_balance from money_accounts where id = :id").param("id", UUID.fromString(bank)).query(Acc.class).single())
                .isEqualTo(new Acc("USD", 120000));
        assertThat(jdbc.sql("select currency, opening_balance from money_accounts where id = :id").param("id", UUID.fromString(usd)).query(Acc.class).single())
                .isEqualTo(new Acc("USD", 50000));
        record Txn(String currency, long amount, int version) {
        }
        assertThat(jdbc.sql("select currency, amount, version from transactions where id = :id").param("id", UUID.fromString(txn)).query(Txn.class).single())
                .isEqualTo(new Txn("USD", 1200, 2));
        assertThat(jdbc.sql("select currency, amount from budget_limits where space_id = :s").param("s", UUID.fromString(space))
                .query((rs, n) -> rs.getString(1) + " " + rs.getLong(2)).single()).isEqualTo("USD 14400");
        mvc.perform(get("/api/core/me").with(me)).andExpect(jsonPath("$.spaces[0].currency").value("USD")).andExpect(jsonPath("$.settings.currency").value("USD"));

        // The ledger: the old entry reversed, the new one posted in dollars.
        List<String> commands = jdbc.sql("select payload::text from outbox_events where payload::text like :t order by id")
                .param("t", "%" + txn + "%").query(String.class).list();
        assertThat(commands).anyMatch(c -> c.contains("reversal")).anyMatch(c -> c.contains("\"USD\"") && c.contains(":v2"));

        mvc.perform(post(base + "/currency").with(me).contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"USD\"}"))
                .andExpect(jsonPath("$.code").value("same-currency"));
    }

    @Test
    void relabellingKeepsTheNumbersAcrossDifferentMinorUnits() throws Exception {
        RequestPostProcessor me = user();
        String space = JsonPath.read(mvc.perform(get("/api/core/me").with(me)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
        String base = "/api/core/spaces/" + space;
        String bank = id(post(base + "/accounts"), me, "{\"kind\":\"BANK\",\"name\":\"Bank\",\"openingBalance\":100000,\"openingDate\":\"2026-01-01\"}");
        // ₹1,000.00 relabelled to yen is ¥1,000 (no minor unit).
        ok(post(base + "/currency"), me, "{\"to\":\"JPY\",\"mode\":\"RELABEL\"}");
        assertThat(jdbc.sql("select opening_balance from money_accounts where id = :id").param("id", UUID.fromString(bank)).query(Long.class).single()).isEqualTo(1000L);
        mvc.perform(post(base + "/currency").with(me).contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"XYZ1\"}"))
                .andExpect(jsonPath("$.code").value("invalid-currency"));
    }

    private static RequestPostProcessor user() {
        String subject = UUID.randomUUID().toString();
        return jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
    }

    private String id(MockHttpServletRequestBuilder request, RequestPostProcessor who, String body) throws Exception {
        return JsonPath.read(ok(request, who, body), "$.id");
    }

    private String ok(MockHttpServletRequestBuilder request, RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(request.with(who).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }
}
