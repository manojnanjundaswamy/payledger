package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The health score and "Can I afford it?" from three months of history, balances and card dues. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InsightsIntegrationTest {

    @DynamicPropertySource
    static void ledgerUrl(DynamicPropertyRegistry registry) {
        registry.add("payledger.ledger.url", FakeLedger::url);
    }

    @Autowired MockMvc mvc;

    @Test
    void steadySaverScoresWellAndGetsHonestAnswers() throws Exception {
        FakeLedger.DOWN.set(false);
        String subject = UUID.randomUUID().toString();
        RequestPostProcessor me = jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
        String profile = mvc.perform(get("/api/core/me").with(me)).andReturn().getResponse().getContentAsString();
        String space = JsonPath.read(profile, "$.spaces[0].id");
        String base = "/api/core/spaces/" + space;

        // Too new to judge.
        mvc.perform(get(base + "/insights/health").with(me)).andExpect(jsonPath("$.score").doesNotExist()).andExpect(jsonPath("$.grade").value("New"));

        String bank = id(post(base + "/accounts"), me, "{\"kind\":\"BANK\",\"name\":\"HDFC\"}");
        String card = id(post(base + "/accounts"), me, "{\"kind\":\"CARD\",\"name\":\"ICICI\"}");
        String categories = mvc.perform(get(base + "/categories").with(me)).andReturn().getResponse().getContentAsString();
        String food = JsonPath.<List<String>>read(categories, "$[?(@.name == 'Groceries')].id").getFirst();
        String salary = JsonPath.<List<String>>read(categories, "$[?(@.kind == 'INCOME')].id").getFirst();
        // ₹1,00,000 in and ₹60,000 out in each of the last three full months: 40% kept.
        LocalDate month = LocalDate.now(ZoneId.of("Asia/Kolkata")).withDayOfMonth(1);
        for (int k = 1; k <= 3; k++) {
            String on = month.minusMonths(k).plusDays(4).toString();
            id(post(base + "/transactions"), me, txn("INCOME", 10_000_000, bank, salary, on));
            id(post(base + "/transactions"), me, txn("EXPENSE", 6_000_000, bank, food, on));
        }
        // ₹4,20,000 in the bank (7 months of spending), ₹20,000 owed on the card (a fifth of a month's income).
        FakeLedger.RESPONSES.put("/api/v2/books/space:" + space + "/balances", """
                [{"accountRef":"account:%s","accountType":"ASSET","currency":"INR","balance":42000000},
                 {"accountRef":"account:%s","accountType":"LIABILITY","currency":"INR","balance":-2000000}]
                """.formatted(bank, card));

        mvc.perform(get(base + "/insights/health").with(me)).andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(100)).andExpect(jsonPath("$.grade").value("Great"))
                .andExpect(jsonPath("$.basis.income").value(10_000_000)).andExpect(jsonPath("$.basis.spent").value(6_000_000))
                .andExpect(jsonPath("$.parts[0].key").value("saving")).andExpect(jsonPath("$.parts[0].detail").value("You keep 40% of what comes in."))
                .andExpect(jsonPath("$.parts[1].key").value("cushion")).andExpect(jsonPath("$.parts[1].status").value("good"));

        // A monthly cost against the ₹40,000 left each month.
        afford(me, base, 1_500_000, true, "YES");
        afford(me, base, 3_000_000, true, "MAYBE");
        afford(me, base, 5_000_000, true, "NO");
        // A one-off: from savings while 3 months of spending (₹1,80,000) stay; else save up for it.
        afford(me, base, 20_000_000, false, "MAYBE");
        mvc.perform(get(base + "/insights/afford").param("amount", "30000000").with(me))
                .andExpect(jsonPath("$.verdict").value("NO")).andExpect(jsonPath("$.headline").value("Not yet: save up first"));
        mvc.perform(get(base + "/insights/afford").param("amount", "0").with(me)).andExpect(jsonPath("$.code").value("invalid-amount"));

        // Someone else's space: not found.
        var stranger = jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("email", UUID.randomUUID() + "@example.com").claim("email_verified", true));
        mvc.perform(get(base + "/insights/health").with(stranger)).andExpect(status().isNotFound());
    }

    private void afford(RequestPostProcessor me, String base, long amount, boolean monthly, String verdict) throws Exception {
        mvc.perform(get(base + "/insights/afford").param("amount", String.valueOf(amount)).param("monthly", String.valueOf(monthly)).with(me))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verdict").value(verdict));
    }

    private static String txn(String kind, long amount, String account, String category, String on) {
        return """
                {"kind":"%s","amount":%d,"accountId":"%s","categoryId":"%s","occurredOn":"%s","description":"x"}
                """.formatted(kind, amount, account, category, on);
    }

    private String id(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, RequestPostProcessor who, String body) throws Exception {
        return JsonPath.read(mvc.perform(request.with(who).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString(), "$.id");
    }
}
