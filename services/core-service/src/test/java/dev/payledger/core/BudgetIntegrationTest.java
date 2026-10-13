package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
import dev.payledger.core.budget.BudgetPeriod;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Budgets against spending from a fake ledger, so every number is known. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BudgetIntegrationTest {

    @DynamicPropertySource
    static void ledgerUrl(DynamicPropertyRegistry registry) {
        registry.add("payledger.ledger.url", FakeLedger::url);
    }

    @Autowired MockMvc mvc;

    RequestPostProcessor me;
    String space;
    String categories;

    @BeforeEach
    void newUser() throws Exception {
        FakeLedger.DOWN.set(false);
        String subject = UUID.randomUUID().toString();
        me = jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
        space = JsonPath.read(mvc.perform(get("/api/core/me").with(me)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
        categories = mvc.perform(get(path("/categories")).with(me)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void eachBudgetShowsSpendingAndStatusAndAParentIncludesItsSubcategories() throws Exception {
        String groceries = category("Groceries");
        String rent = category("Rent");
        String eatingOut = category("Eating out");
        String snacks = JsonPath.read(mvc.perform(post(path("/categories")).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"EXPENSE\",\"name\":\"Snacks\",\"icon\":\"cookie\",\"color\":\"series-1\",\"parentId\":\"" + groceries + "\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        set(groceries, 1_000_000, "2026-08").andExpect(status().isOk());
        set(rent, 2_500_000, "2026-08");
        set(eatingOut, 500_000, "2026-08");
        ledgerSpends("2026-08-01", "2026-08-31",
                groceries, 600_000, snacks, 250_000, rent, 2_500_000, eatingOut, 700_000, category("Travel"), 900_000);

        mvc.perform(get(path("/budgets")).with(me).param("month", "2026-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-08"))
                .andExpect(jsonPath("$.start").value("2026-08-01")).andExpect(jsonPath("$.end").value("2026-08-31"))
                .andExpect(jsonPath("$.days").value(31)).andExpect(jsonPath("$.spentKnown").value(true))
                // Only budgeted categories, in the categories' order (Travel has spending but no budget).
                .andExpect(jsonPath("$.budgets[*].categoryId").value(org.hamcrest.Matchers.contains(groceries, eatingOut, rent)))
                // ₹6,000 + ₹2,500 of Snacks = 85% of ₹10,000: close.
                .andExpect(jsonPath("$.budgets[0].spent").value(850_000)).andExpect(jsonPath("$.budgets[0].remaining").value(150_000))
                .andExpect(jsonPath("$.budgets[0].status").value("CLOSE"))
                .andExpect(jsonPath("$.budgets[1].status").value("OVER")).andExpect(jsonPath("$.budgets[1].remaining").value(-200_000))
                // Exactly the limit is not over.
                .andExpect(jsonPath("$.budgets[2].status").value("CLOSE"))
                // A past month has no pace.
                .andExpect(jsonPath("$.budgets[0].projected").doesNotExist());
    }

    @Test
    void changingALimitAppliesFromThatMonthOnAndLeavesEarlierMonthsAlone() throws Exception {
        String groceries = category("Groceries");
        set(groceries, 1_000_000, "2026-08");
        set(groceries, 1_500_000, "2026-10");
        expectLimits(groceries, 1_000_000, 1_000_000, 1_500_000, 1_500_000);

        // A change in September replaces the October one too.
        set(groceries, 800_000, "2026-09").andExpect(jsonPath("$.month").value("2026-09"))
                .andExpect(jsonPath("$.budgets[0].limit").value(800_000));
        expectLimits(groceries, 1_000_000, 800_000, 800_000, 800_000);

        mvc.perform(delete(path("/budgets/" + groceries)).with(me).param("from", "2026-11")).andExpect(status().isNoContent());
        expectLimits(groceries, 1_000_000, 800_000, 800_000, null);
        // Ending it before it ever started leaves nothing.
        mvc.perform(delete(path("/budgets/" + groceries)).with(me).param("from", "2026-07")).andExpect(status().isNoContent());
        expectLimits(groceries, null, null, null, null);
    }

    @Test
    void theMonthFollowsTheUsersStartDay() throws Exception {
        mvc.perform(patch("/api/core/me").with(me).contentType(MediaType.APPLICATION_JSON).content("{\"budgetStartDay\":25}"))
                .andExpect(status().isOk());
        String groceries = category("Groceries");
        set(groceries, 1_000_000, "2026-10");
        ledgerSpends("2026-10-25", "2026-11-24", groceries, 1_200_000);

        mvc.perform(get(path("/budgets")).with(me).param("month", "2026-10"))
                .andExpect(jsonPath("$.start").value("2026-10-25")).andExpect(jsonPath("$.end").value("2026-11-24"))
                .andExpect(jsonPath("$.days").value(31))
                .andExpect(jsonPath("$.budgets[0].spent").value(1_200_000)).andExpect(jsonPath("$.budgets[0].status").value("OVER"));
    }

    @Test
    void withoutAMonthItIsTheCurrentOne() throws Exception {
        String groceries = category("Groceries");
        set(groceries, 1_000_000, null).andExpect(status().isOk());
        String month = JsonPath.read(mvc.perform(get(path("/budgets")).with(me))
                .andExpect(jsonPath("$.budgets[0].limit").value(1_000_000))
                .andExpect(jsonPath("$.daysElapsed").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andReturn().getResponse().getContentAsString(), "$.month");
        mvc.perform(get(path("/budgets")).with(me).param("month", month)).andExpect(jsonPath("$.budgets[0].limit").value(1_000_000));
    }

    @Test
    void whenTheLedgerIsDownTheLimitsStillShow() throws Exception {
        String groceries = category("Groceries");
        set(groceries, 1_000_000, "2026-08");
        FakeLedger.DOWN.set(true);
        mvc.perform(get(path("/budgets")).with(me).param("month", "2026-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spentKnown").value(false))
                .andExpect(jsonPath("$.budgets[0].limit").value(1_000_000))
                .andExpect(jsonPath("$.budgets[0].spent").doesNotExist())
                .andExpect(jsonPath("$.budgets[0].status").doesNotExist());
    }

    @Test
    void badBudgetsAreRefused() throws Exception {
        set(category("Salary"), 100, "2026-08").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("budget-category-invalid"));
        set(category("Groceries"), 0, "2026-08").andExpect(status().isBadRequest());
        set(UUID.randomUUID().toString(), 100, "2026-08").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("category-not-found"));
        mvc.perform(get(path("/budgets")).with(me).param("month", "2026-13")).andExpect(status().isBadRequest());

        String travel = category("Travel");
        mvc.perform(put(path("/categories/" + travel)).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Travel\",\"icon\":\"plane\",\"color\":\"series-5\",\"parentId\":null,\"hidden\":true}"));
        set(travel, 100, "2026-08").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("budget-category-invalid"));

        String stranger = UUID.randomUUID().toString();
        var them = jwt().jwt(j -> j.subject(stranger).claim("email", stranger + "@example.com").claim("email_verified", true));
        mvc.perform(get(path("/budgets")).with(them)).andExpect(status().isNotFound());
    }

    @Test
    void anUnusedCategoryWithABudgetCanStillBeDeleted() throws Exception {
        String pets = JsonPath.read(mvc.perform(post(path("/categories")).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"EXPENSE\",\"name\":\"Pets\",\"icon\":\"paw-print\",\"color\":\"series-1\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        set(pets, 300_000, "2026-08");
        mvc.perform(delete(path("/categories/" + pets)).with(me)).andExpect(status().isNoContent());
        mvc.perform(get(path("/budgets")).with(me).param("month", "2026-08")).andExpect(jsonPath("$.budgets.length()").value(0));
    }

    @Test
    void safeToSpendIsIncomeLessSpendingBillsStillDueAndSavings() throws Exception {
        // The current month (start day 1, the default) in the user's time zone.
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        BudgetPeriod period = BudgetPeriod.containing(today, 1);
        String bank = createAccount("HDFC");
        String zerodha = createAccount("Zerodha");
        String emi = category("EMIs & loans");
        String rent = commitment("""
                {"kind":"BILL","name":"Rent","amount":2500000,"accountId":"%s","categoryId":"%s","everyMonths":1,"firstDue":"%s"}
                """.formatted(bank, emi, period.start()));
        commitment("""
                {"kind":"BILL","name":"Car EMI","amount":1000000,"accountId":"%s","categoryId":"%s","everyMonths":1,"firstDue":"%s"}
                """.formatted(bank, emi, period.end()));
        commitment("""
                {"kind":"SAVING","name":"SIP","amount":500000,"accountId":"%s","toAccountId":"%s","everyMonths":1,"firstDue":"%s"}
                """.formatted(bank, zerodha, period.start()));
        // Rent is paid: it is in "spent" now (the fake ledger's spending below includes it), not in bills left.
        mvc.perform(post(path("/commitments/" + rent + "/payments")).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueDate\":\"" + period.start() + "\"}")).andExpect(status().isCreated());
        String totals = "/api/v2/books/space:%s/totals?type=%s&from=%s&to=%s&byMonth=false";
        FakeLedger.RESPONSES.put(totals.formatted(space, "INCOME", period.start(), period.end()), """
                [{"accountRef":"category:%s","accountType":"INCOME","currency":"INR","periodStart":null,"total":-10000000}]
                """.formatted(category("Salary")));
        ledgerSpends(period.start().toString(), period.end().toString(), emi, 3_000_000);

        int daysLeft = period.days() - period.daysElapsed(today) + 1;
        // ₹1,00,000 - ₹30,000 - ₹10,000 EMI still due - ₹5,000 SIP = ₹55,000.
        mvc.perform(get(path("/safe-to-spend")).with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.start").value(period.start().toString()))
                .andExpect(jsonPath("$.known").value(true))
                .andExpect(jsonPath("$.income").value(10_000_000)).andExpect(jsonPath("$.spent").value(3_000_000))
                .andExpect(jsonPath("$.billsLeft").value(1_000_000)).andExpect(jsonPath("$.savings").value(500_000))
                .andExpect(jsonPath("$.safeToSpend").value(5_500_000))
                .andExpect(jsonPath("$.daysLeft").value(daysLeft))
                .andExpect(jsonPath("$.perDay").value(5_500_000 / daysLeft));

        FakeLedger.DOWN.set(true);
        mvc.perform(get(path("/safe-to-spend")).with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.known").value(false))
                .andExpect(jsonPath("$.safeToSpend").doesNotExist())
                .andExpect(jsonPath("$.billsLeft").value(1_000_000));
    }

    // ---------------------------------------------------------------- helpers

    private String createAccount(String name) throws Exception {
        return JsonPath.read(mvc.perform(post(path("/accounts")).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"BANK\",\"name\":\"" + name + "\"}")).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String commitment(String body) throws Exception {
        return JsonPath.read(mvc.perform(post(path("/commitments")).with(me).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String path(String rest) {
        return "/api/core/spaces/" + space + rest;
    }

    private String category(String name) {
        return JsonPath.<List<String>>read(categories, "$[?(@.name == '" + name + "')].id").getFirst();
    }

    private ResultActions set(String categoryId, long amount, String from) throws Exception {
        String body = from == null ? "{\"amount\":%d}".formatted(amount) : "{\"amount\":%d,\"from\":\"%s\"}".formatted(amount, from);
        return mvc.perform(put(path("/budgets/" + categoryId)).with(me).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** The limit for August to November 2026 (null: no budget that month). */
    private void expectLimits(String categoryId, Integer... limits) throws Exception {
        String[] months = {"2026-08", "2026-09", "2026-10", "2026-11"};
        for (int i = 0; i < months.length; i++) {
            var result = mvc.perform(get(path("/budgets")).with(me).param("month", months[i]));
            if (limits[i] == null) {
                result.andExpect(jsonPath("$.budgets[?(@.categoryId == '" + categoryId + "')]").isEmpty());
            } else {
                result.andExpect(jsonPath("$.budgets[?(@.categoryId == '" + categoryId + "')].limit").value(limits[i]));
            }
        }
    }

    /** Pairs of category id and amount spent between the dates. */
    private void ledgerSpends(String from, String to, Object... pairs) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < pairs.length; i += 2) {
            json.append(i == 0 ? "" : ",").append("""
                    {"accountRef":"category:%s","accountType":"EXPENSE","currency":"INR","periodStart":null,"total":%d}
                    """.formatted(pairs[i], ((Number) pairs[i + 1]).longValue()));
        }
        FakeLedger.RESPONSES.put("/api/v2/books/space:%s/totals?type=EXPENSE&from=%s&to=%s&byMonth=false".formatted(space, from, to),
                json.append("]").toString());
    }
}
