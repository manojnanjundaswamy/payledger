package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
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

import java.util.UUID;

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

    @DynamicPropertySource
    static void ledgerUrl(DynamicPropertyRegistry registry) {
        registry.add("payledger.ledger.url", FakeLedger::url);
    }

    @Autowired MockMvc mvc;

    RequestPostProcessor me;
    String space;

    @BeforeEach
    void newUser() throws Exception {
        FakeLedger.DOWN.set(false);
        String subject = UUID.randomUUID().toString();
        me = jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
        space = JsonPath.read(mvc.perform(get("/api/core/me").with(me)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
    }

    @Test
    void accountBalancesAreShownInTheUsersTerms() throws Exception {
        String bank = createAccount("BANK", "HDFC");
        String card = createAccount("CARD", "ICICI");
        String cash = createAccount("CASH", "Cash");
        FakeLedger.RESPONSES.put("/api/v2/books/space:" + space + "/balances", """
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
        FakeLedger.DOWN.set(true);
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
        FakeLedger.RESPONSES.put("/api/v2/books/space:%s/totals?type=EXPENSE&from=2026-10-01&to=2026-10-31&byMonth=false".formatted(space), """
                [{"accountRef":"category:%s","accountType":"EXPENSE","currency":"INR","periodStart":null,"total":48600}]
                """.formatted(groceries));
        FakeLedger.RESPONSES.put("/api/v2/books/space:%s/totals?type=INCOME&from=2026-10-01&to=2026-10-31&byMonth=false".formatted(space), """
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

    @Test
    void netWorthByMonthIsTodaysBalancesLessWhatMovedLater() throws Exception {
        String a = createAccount("BANK", "HDFC");
        String c = createAccount("CARD", "ICICI");
        String archived = createAccount("BANK", "Old bank");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(path("/accounts/" + archived)).with(me)
                .contentType(MediaType.APPLICATION_JSON).content("{\"archived\":true}")).andExpect(status().isOk());
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        java.time.YearMonth now = java.time.YearMonth.from(today);
        FakeLedger.RESPONSES.put("/api/v2/books/space:" + space + "/balances", """
                [{"accountRef":"account:%s","accountType":"ASSET","currency":"INR","balance":5000000},
                 {"accountRef":"account:%s","accountType":"LIABILITY","currency":"INR","balance":-1000000},
                 {"accountRef":"account:%s","accountType":"ASSET","currency":"INR","balance":3300000},
                 {"accountRef":"account:u","accountType":"ASSET","currency":"USD","balance":999999},
                 {"accountRef":"category:x","accountType":"EXPENSE","currency":"INR","balance":700000}]
                """.formatted(a, c, archived));
        String totals = "/api/v2/books/space:%s/totals?type=%s&from=%s&to=%s&byMonth=true";
        String from = now.minusMonths(1).atDay(1).toString();
        String to = today.plusYears(50).toString();
        // Last month the bank grew by ₹20,000; this month the card's debt grew by ₹5,000. The archived
        // account's money is left out, as on the Accounts page.
        FakeLedger.RESPONSES.put(totals.formatted(space, "ASSET", from, to), """
                [{"accountRef":"account:%s","accountType":"ASSET","currency":"INR","periodStart":"%s","total":2000000},
                 {"accountRef":"account:%s","accountType":"ASSET","currency":"INR","periodStart":"%s","total":3300000}]
                """.formatted(a, now.minusMonths(1).atDay(1), archived, now.atDay(1)));
        FakeLedger.RESPONSES.put(totals.formatted(space, "LIABILITY", from, to), """
                [{"accountRef":"account:%s","accountType":"LIABILITY","currency":"INR","periodStart":"%s","total":-500000}]
                """.formatted(c, now.atDay(1)));

        mvc.perform(get(path("/net-worth")).with(me).param("months", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].month").value(now.toString())).andExpect(jsonPath("$[2].netWorth").value(4_000_000))
                .andExpect(jsonPath("$[1].netWorth").value(4_500_000))
                .andExpect(jsonPath("$[0].netWorth").value(2_500_000));
        mvc.perform(get(path("/net-worth")).with(me).param("months", "1")).andExpect(status().isBadRequest());
        FakeLedger.DOWN.set(true);
        mvc.perform(get(path("/net-worth")).with(me)).andExpect(status().isServiceUnavailable());
    }

    private String path(String rest) {
        return "/api/core/spaces/" + space + rest;
    }

    private String createAccount(String kind, String name) throws Exception {
        return JsonPath.read(mvc.perform(post(path("/accounts")).with(me).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"%s\",\"name\":\"%s\"}".formatted(kind, name)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }
}
