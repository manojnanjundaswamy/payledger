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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The two-minute setup after sign-up, and budgets suggested from history. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SetupIntegrationTest {

    @DynamicPropertySource
    static void ledger(DynamicPropertyRegistry registry) {
        registry.add("payledger.ledger.url", FakeLedger::url);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    RequestPostProcessor me;
    String space;

    @BeforeEach
    void newUser() throws Exception {
        String subject = UUID.randomUUID().toString();
        me = jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
        space = JsonPath.read(mvc.perform(get("/api/core/me").with(me)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
    }

    @Test
    void oneCallSetsUpPaydayAccountsRegularPaymentsAndBudgets() throws Exception {
        setup("""
                {"budgetStartDay":25,
                 "accounts":[{"kind":"BANK","name":"HDFC salary","openingBalance":5000000},{"kind":"CARD","name":"ICICI card"},{"kind":"CASH","name":"Cash"}],
                 "payments":[{"kind":"BILL","name":"Rent","amount":2500000,"category":"rent","day":5,"everyMonths":1},
                             {"kind":"BILL","name":"Netflix","amount":64900,"category":"subscriptions","account":1,"day":31,"everyMonths":1},
                             {"kind":"SAVING","name":"Index fund SIP","amount":500000,"day":10,"everyMonths":1}],
                 "budgets":[{"category":"groceries","amount":800000},{"category":"eating-out","amount":400000}]}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accounts.length()").value(4))
                .andExpect(jsonPath("$.accounts[3].name").value("Savings and investments"))
                .andExpect(jsonPath("$.payments.length()").value(3))
                .andExpect(jsonPath("$.budgets").value(2));

        mvc.perform(get("/api/core/me").with(me)).andExpect(jsonPath("$.settings.budgetStartDay").value(25));
        String commitments = mvc.perform(get(path("/commitments")).with(me)).andReturn().getResponse().getContentAsString();
        List<String> names = JsonPath.read(commitments, "$[*].name");
        assertThat(names).containsExactlyInAnyOrder("Rent", "Netflix", "Index fund SIP");
        // Netflix is paid from the card (index 1), the others from the first bank account.
        String card = ((List<?>) JsonPath.read(commitments, "$[?(@.name == 'Netflix')].accountId")).getFirst().toString();
        assertThat(jdbc.sql("select name from money_accounts where id = ?").param(UUID.fromString(card)).query(String.class).single()).isEqualTo("ICICI card");
        mvc.perform(get(path("/budgets")).with(me)).andExpect(jsonPath("$.budgets.length()").value(2));
    }

    @Test
    void aSecondSetupIsRefusedSoARetryCantDoubleEverything() throws Exception {
        String body = """
                {"accounts":[{"kind":"BANK","name":"SBI"}],"payments":[],"budgets":[]}
                """;
        setup(body).andExpect(status().isCreated());
        setup(body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already-set-up"));
        assertThat(count("money_accounts")).isEqualTo(1);
    }

    @Test
    void itIsAllOrNothing() throws Exception {
        // The budget names a category that doesn't exist: nothing of the setup stays.
        setup("""
                {"budgetStartDay":10,"accounts":[{"kind":"BANK","name":"Axis"}],
                 "payments":[{"kind":"BILL","name":"Rent","amount":1000000,"category":"rent","day":1,"everyMonths":1}],
                 "budgets":[{"category":"yachts","amount":100}]}
                """)
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("setup-invalid"));
        assertThat(count("money_accounts")).isZero();
        assertThat(count("commitments")).isZero();
        mvc.perform(get("/api/core/me").with(me)).andExpect(jsonPath("$.settings.budgetStartDay").value(1));

        // Free allows 5 open accounts: six is refused as a whole.
        StringBuilder six = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            six.append(i > 1 ? "," : "").append("{\"kind\":\"BANK\",\"name\":\"Bank ").append(i).append("\"}");
        }
        setup("{\"accounts\":[" + six + "],\"payments\":[],\"budgets\":[]}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan-limit"));
        assertThat(count("money_accounts")).isZero();
    }

    @Test
    void dueDatesFallOnTheDayOrTheMonthsLastDay() {
        assertThat(dev.payledger.core.money.SetupService.nextDue(LocalDate.of(2026, 1, 31), 31)).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(dev.payledger.core.money.SetupService.nextDue(LocalDate.of(2026, 2, 1), 31)).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(dev.payledger.core.money.SetupService.nextDue(LocalDate.of(2026, 10, 14), 5)).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(dev.payledger.core.money.SetupService.nextDue(LocalDate.of(2026, 10, 14), 14)).isEqualTo(LocalDate.of(2026, 10, 14));
    }

    @Test
    void budgetsAreSuggestedFromTheLastThreeMonths() throws Exception {
        String groceries = category("Groceries");
        String eatingOut = category("Eating out");
        String rent = category("Rent");
        setup("{\"accounts\":[{\"kind\":\"BANK\",\"name\":\"HDFC\"}],\"payments\":[],\"budgets\":[{\"category\":\"rent\",\"amount\":2500000}]}")
                .andExpect(status().isCreated());
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        BudgetPeriod current = BudgetPeriod.containing(today, 1);
        LocalDate from = BudgetPeriod.of(current.month().minusMonths(3), 1).start();
        LocalDate to = current.start().minusDays(1);
        // Three months: groceries ₹24,100 (₹8,033 a month -> ₹8,500), eating out ₹200 (too small), rent already budgeted.
        FakeLedger.RESPONSES.put("/api/v2/books/space:%s/totals?type=EXPENSE&from=%s&to=%s&byMonth=false".formatted(space, from, to), """
                [{"accountRef":"category:%s","accountType":"EXPENSE","currency":"INR","periodStart":null,"total":2410000},
                 {"accountRef":"category:%s","accountType":"EXPENSE","currency":"INR","periodStart":null,"total":20000},
                 {"accountRef":"category:%s","accountType":"EXPENSE","currency":"INR","periodStart":null,"total":7500000}]
                """.formatted(groceries, eatingOut, rent));
        mvc.perform(get(path("/budgets/suggestions")).with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].categoryId").value(groceries))
                .andExpect(jsonPath("$[0].average").value(803333))
                .andExpect(jsonPath("$[0].suggested").value(850000));
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions setup(String body) throws Exception {
        return mvc.perform(post(path("/setup")).with(me).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long count(String table) {
        return jdbc.sql("select count(*) from " + table + " where space_id = ?").param(UUID.fromString(space)).query(Long.class).single();
    }

    private String category(String name) throws Exception {
        String all = mvc.perform(get(path("/categories")).with(me)).andReturn().getResponse().getContentAsString();
        return ((List<?>) JsonPath.read(all, "$[?(@.name == '" + name + "')].id")).getFirst().toString();
    }

    private String path(String rest) {
        return "/api/core/spaces/" + space + rest;
    }
}
