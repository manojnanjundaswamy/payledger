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
import org.springframework.jdbc.core.simple.JdbcClient;
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

/** The annual planner against today's real date (start day 1), with a fake ledger for safe to spend. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PlannerIntegrationTest {

    @DynamicPropertySource
    static void ledgerUrl(DynamicPropertyRegistry registry) {
        registry.add("payledger.ledger.url", FakeLedger::url);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    RequestPostProcessor me;
    String space;
    String bank;
    String insurance;
    String family;
    BudgetPeriod period;

    @BeforeEach
    void newUser() throws Exception {
        FakeLedger.DOWN.set(false);
        String subject = UUID.randomUUID().toString();
        me = jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
        space = JsonPath.read(mvc.perform(get("/api/core/me").with(me)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
        String categories = mvc.perform(get(path("/categories")).with(me)).andReturn().getResponse().getContentAsString();
        insurance = JsonPath.<List<String>>read(categories, "$[?(@.name == 'Insurance')].id").getFirst();
        family = JsonPath.<List<String>>read(categories, "$[?(@.name == 'Family & gifts')].id").getFirst();
        bank = JsonPath.read(mvc.perform(post(path("/accounts")).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"BANK\",\"name\":\"HDFC\"}")).andReturn().getResponse().getContentAsString(), "$.id");
        period = BudgetPeriod.containing(LocalDate.now(ZoneId.of("Asia/Kolkata")), 1);
    }

    @Test
    void bigCostsAreSpreadOverTheMonthsLeftAndOrdinaryBillsArentPlanned() throws Exception {
        LocalDate inThreeMonths = period.start().plusMonths(3).withDayOfMonth(10);
        LocalDate inSixMonths = period.start().plusMonths(6).withDayOfMonth(20);
        bill("Health insurance", 1_800_000, 12, inThreeMonths, null, insurance);
        // Once: its last payment is its first.
        bill("Wedding gift", 3_000_000, 1, inSixMonths, inSixMonths, family);
        bill("Netflix", 64_900, 1, period.start(), null, family);
        bill("Car insurance", 900_000, 3, period.start(), null, insurance);

        mvc.perform(get(path("/planner")).with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].name").value("Health insurance"))
                .andExpect(jsonPath("$.items[0].nextDue").value(inThreeMonths.toString()))
                .andExpect(jsonPath("$.items[0].monthsLeft").value(3))
                .andExpect(jsonPath("$.items[0].monthlySetAside").value(600_000))
                .andExpect(jsonPath("$.items[1].oneOff").value(true))
                .andExpect(jsonPath("$.items[1].monthsLeft").value(6))
                .andExpect(jsonPath("$.items[1].monthlySetAside").value(500_000))
                .andExpect(jsonPath("$.monthlySetAside").value(1_100_000));
    }

    @Test
    void inItsOwnMonthACostThatWasSetAsideForDoesntCountAgainInSafeToSpend() throws Exception {
        String premium = bill("Term insurance", 2_400_000, 12, period.end(), null, insurance);
        String late = bill("Diwali gifts", 1_000_000, 12, period.end(), null, family);
        // The premium was planned last month (so set aside for); Diwali gifts only now.
        jdbc.sql("update commitments set created_at = :at where id = :id")
                .param("at", java.sql.Timestamp.valueOf(period.start().minusMonths(1).atStartOfDay()))
                .param("id", UUID.fromString(premium)).update();
        income(10_000_000);
        spending(0);

        mvc.perform(get(path("/planner")).with(me))
                .andExpect(jsonPath("$.items[?(@.name == 'Term insurance')].covered").value(true))
                .andExpect(jsonPath("$.items[?(@.name == 'Diwali gifts')].covered").value(false))
                .andExpect(jsonPath("$.monthlySetAside").value(0));
        // Only the gifts are still a bill this month: ₹1,00,000 - ₹10,000.
        mvc.perform(get(path("/safe-to-spend")).with(me))
                .andExpect(jsonPath("$.billsLeft").value(1_000_000))
                .andExpect(jsonPath("$.setAside").value(0))
                .andExpect(jsonPath("$.safeToSpend").value(9_000_000));

        // Paying the premium doesn't make it "spent" either.
        mvc.perform(post(path("/commitments/" + premium + "/payments")).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueDate\":\"" + period.end() + "\"}")).andExpect(status().isCreated());
        spending(2_400_000);
        mvc.perform(get(path("/safe-to-spend")).with(me))
                .andExpect(jsonPath("$.spent").value(0))
                .andExpect(jsonPath("$.safeToSpend").value(9_000_000));
        mvc.perform(get(path("/planner")).with(me))
                .andExpect(jsonPath("$.items[?(@.name == 'Term insurance')].paid").value(true))
                .andExpect(jsonPath("$.items[?(@.name == 'Term insurance')].monthsLeft").value(0));

        // The gifts, paid, are ordinary spending this month.
        mvc.perform(post(path("/commitments/" + late + "/payments")).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueDate\":\"" + period.end() + "\"}")).andExpect(status().isCreated());
        spending(3_400_000);
        mvc.perform(get(path("/safe-to-spend")).with(me))
                .andExpect(jsonPath("$.spent").value(1_000_000)).andExpect(jsonPath("$.billsLeft").value(0))
                .andExpect(jsonPath("$.safeToSpend").value(9_000_000));
    }

    @Test
    void setAsidesComeOffSafeToSpendEveryMonthBeforeTheCost() throws Exception {
        bill("School fees", 6_000_000, 12, period.start().plusMonths(4).withDayOfMonth(5), null, family);
        income(10_000_000);
        spending(2_000_000);
        // ₹1,00,000 - ₹20,000 - ₹60,000 / 4 = ₹65,000.
        mvc.perform(get(path("/safe-to-spend")).with(me))
                .andExpect(jsonPath("$.setAside").value(1_500_000))
                .andExpect(jsonPath("$.safeToSpend").value(6_500_000));
    }

    // ---------------------------------------------------------------- helpers

    private String path(String rest) {
        return "/api/core/spaces/" + space + rest;
    }

    private String bill(String name, long amount, int everyMonths, LocalDate firstDue, LocalDate endsOn, String category) throws Exception {
        return JsonPath.read(mvc.perform(post(path("/commitments")).with(me).contentType(MediaType.APPLICATION_JSON).content("""
                        {"kind":"BILL","name":"%s","amount":%d,"accountId":"%s","categoryId":"%s","everyMonths":%d,"firstDue":"%s","endsOn":%s}
                        """.formatted(name, amount, bank, category, everyMonths, firstDue, endsOn == null ? "null" : "\"" + endsOn + "\"")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private void income(long amount) {
        FakeLedger.RESPONSES.put(totals("INCOME"), """
                [{"accountRef":"category:%s","accountType":"INCOME","currency":"INR","periodStart":null,"total":%d}]
                """.formatted(UUID.randomUUID(), -amount));
    }

    private void spending(long amount) {
        FakeLedger.RESPONSES.put(totals("EXPENSE"), """
                [{"accountRef":"category:%s","accountType":"EXPENSE","currency":"INR","periodStart":null,"total":%d}]
                """.formatted(insurance, amount));
    }

    private String totals(String type) {
        return "/api/v2/books/space:%s/totals?type=%s&from=%s&to=%s&byMonth=false".formatted(space, type, period.start(), period.end());
    }
}
