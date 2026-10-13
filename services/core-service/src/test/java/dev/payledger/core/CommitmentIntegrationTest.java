package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CommitmentIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    RequestPostProcessor me;
    String space;
    String emi;
    String salary;
    String bank;
    String investments;

    @BeforeEach
    void newUser() throws Exception {
        String subject = UUID.randomUUID().toString();
        me = jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
        space = JsonPath.read(mvc.perform(get("/api/core/me").with(me)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
        String categories = mvc.perform(get(path("/categories")).with(me)).andReturn().getResponse().getContentAsString();
        emi = JsonPath.<List<String>>read(categories, "$[?(@.name == 'EMIs & loans')].id").getFirst();
        salary = JsonPath.<List<String>>read(categories, "$[?(@.name == 'Salary')].id").getFirst();
        bank = account("HDFC");
        investments = account("Zerodha");
    }

    @Test
    void aMonthlyEmiIsDueOnItsDayUntilItsLastInstalment() throws Exception {
        String id = id(create(bill("Car EMI", 1_250_000, 1, "2026-01-31", "2026-06-30")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.everyMonths").value(1)).andExpect(jsonPath("$.version").value(1)));
        create(saving("SIP", 500_000, 3, "2026-02-10")).andExpect(status().isCreated());

        mvc.perform(get(path("/commitments")).with(me)).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get(path("/commitments/due")).with(me).param("from", "2026-01-01").param("to", "2026-12-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.commitmentId == '" + id + "')].dueDate").value(contains(
                        "2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30", "2026-05-31", "2026-06-30")))
                .andExpect(jsonPath("$[?(@.name == 'SIP')].dueDate").value(contains("2026-02-10", "2026-05-10", "2026-08-10", "2026-11-10")))
                .andExpect(jsonPath("$[0].paid").value(false));
    }

    @Test
    void payingABillRecordsAnExpenseOnceAndDeletingItMakesTheDateDueAgain() throws Exception {
        String id = id(create(bill("Car EMI", 1_250_000, 1, "2026-09-05", null)));

        String txn = JsonPath.read(pay(id, "{\"dueDate\":\"2026-10-05\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.paid").value(true)).andExpect(jsonPath("$.amount").value(1_250_000))
                .andReturn().getResponse().getContentAsString(), "$.transactionId");
        mvc.perform(get(path("/transactions/" + txn)).with(me))
                .andExpect(jsonPath("$.kind").value("EXPENSE")).andExpect(jsonPath("$.categoryId").value(emi))
                .andExpect(jsonPath("$.accountId").value(bank)).andExpect(jsonPath("$.occurredOn").value("2026-10-05"))
                .andExpect(jsonPath("$.description").value("Car EMI"));
        assertThat(commands()).isEqualTo(1);

        pay(id, "{\"dueDate\":\"2026-10-05\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already-paid"));
        expectPaid(id, "2026-10-05", true);

        mvc.perform(delete(path("/transactions/" + txn)).with(me).param("version", "1")).andExpect(status().isNoContent());
        expectPaid(id, "2026-10-05", false);
        // Paid again, a different amount on a different day.
        pay(id, "{\"dueDate\":\"2026-10-05\",\"amount\":1300000,\"occurredOn\":\"2026-10-07\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(1_300_000));
        expectPaid(id, "2026-10-05", true);

        pay(id, "{\"dueDate\":\"2026-10-06\"}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("not-a-due-date"));
    }

    @Test
    void payingASavingRecordsATransfer() throws Exception {
        String id = id(create(saving("SIP", 500_000, 1, "2026-10-10")));
        String txn = JsonPath.read(pay(id, "{\"dueDate\":\"2026-10-10\"}").andReturn().getResponse().getContentAsString(), "$.transactionId");
        mvc.perform(get(path("/transactions/" + txn)).with(me))
                .andExpect(jsonPath("$.kind").value("TRANSFER")).andExpect(jsonPath("$.accountId").value(bank))
                .andExpect(jsonPath("$.toAccountId").value(investments));
    }

    @Test
    void tappingPaidManyTimesAtOncePaysOnce() throws Exception {
        String id = id(create(bill("Rent", 2_500_000, 1, "2026-10-01", null)));
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> pay(id, "{\"dueDate\":\"2026-10-01\"}").andReturn().getResponse().getStatus()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsOnly(201, 409).containsOnlyOnce(201);
        }
        assertThat(jdbc.sql("select count(*) from transactions where space_id = ?").param(UUID.fromString(space)).query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void steadyMonthlyExpensesAreSuggestedAsBills() throws Exception {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        String subscriptions = JsonPath.<List<String>>read(mvc.perform(get(path("/categories")).with(me))
                .andReturn().getResponse().getContentAsString(), "$[?(@.name == 'Subscriptions')].id").getFirst();
        for (int back = 1; back <= 4; back++) {
            java.time.LocalDate month = today.minusMonths(back);
            expense("Netflix", 64_900, subscriptions, month.withDayOfMonth(Math.min(14 + back % 2, month.lengthOfMonth())));
            // Groceries come every month, but never the same amount: not a bill.
            expense("Big Bazaar", 10_000 * back * back, emi, month.withDayOfMonth(10));
            // Already on the plan.
            expense("Rent", 2_500_000, emi, month.withDayOfMonth(2));
        }
        // A gym membership that stopped four months ago.
        for (int back = 4; back <= 6; back++) {
            expense("Gym", 150_000, subscriptions, today.minusMonths(back).withDayOfMonth(5));
        }
        create(bill("Rent", 2_500_000, 1, today.withDayOfMonth(2).toString(), null)).andExpect(status().isCreated());

        mvc.perform(get(path("/commitments/suggestions")).with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Netflix")).andExpect(jsonPath("$[0].amount").value(64_900))
                .andExpect(jsonPath("$[0].months").value(4)).andExpect(jsonPath("$[0].accountId").value(bank))
                .andExpect(jsonPath("$[0].categoryId").value(subscriptions))
                .andExpect(jsonPath("$[0].dayOfMonth").value(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(14), org.hamcrest.Matchers.is(15))))
                .andExpect(jsonPath("$[0].nextDue").value(org.hamcrest.Matchers.greaterThan(today.toString())));
    }

    @Test
    void editingNeedsTheCurrentVersionAndDeletingKeepsThePayments() throws Exception {
        String id = id(create(bill("Netflix", 64_900, 1, "2026-10-15", null)));
        mvc.perform(put(path("/commitments/" + id)).with(me).param("version", "1").contentType(MediaType.APPLICATION_JSON)
                        .content(bill("Netflix Premium", 64_900, 1, "2026-10-15", null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Netflix Premium")).andExpect(jsonPath("$.version").value(2));
        mvc.perform(put(path("/commitments/" + id)).with(me).param("version", "1").contentType(MediaType.APPLICATION_JSON)
                        .content(bill("Stale", 64_900, 1, "2026-10-15", null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("version-conflict"));

        String txn = JsonPath.read(pay(id, "{\"dueDate\":\"2026-10-15\"}").andReturn().getResponse().getContentAsString(), "$.transactionId");
        mvc.perform(delete(path("/commitments/" + id)).with(me)).andExpect(status().isNoContent());
        mvc.perform(get(path("/commitments")).with(me)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(path("/transactions/" + txn)).with(me)).andExpect(status().isOk());
        mvc.perform(delete(path("/commitments/" + id)).with(me)).andExpect(status().isNotFound());
    }

    @Test
    void badCommitmentsAreRefused() throws Exception {
        create(bill("No category", 100, 1, "2026-10-01", null).replace("\"categoryId\":\"" + emi + "\"", "\"categoryId\":null"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-commitment"));
        create(bill("Income?", 100, 1, "2026-10-01", null).replace(emi, salary))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("category-kind-mismatch"));
        create(saving("To itself", 100, 1, "2026-10-01").replace(investments, bank))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-commitment"));
        create(bill("Every 5", 100, 5, "2026-10-01", null)).andExpect(status().isBadRequest());
        create(bill("Ends early", 100, 1, "2026-10-01", "2026-09-01"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-commitment"));
        create(bill("Zero", 0, 1, "2026-10-01", null)).andExpect(status().isBadRequest());
        mvc.perform(get(path("/commitments/due")).with(me).param("from", "2026-01-01").param("to", "2030-01-01"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-range"));

        String stranger = UUID.randomUUID().toString();
        var them = jwt().jwt(j -> j.subject(stranger).claim("email", stranger + "@example.com").claim("email_verified", true));
        mvc.perform(get(path("/commitments")).with(them)).andExpect(status().isNotFound());
    }

    @Test
    void aCategoryABillUsesCantBeDeleted() throws Exception {
        String gym = id(mvc.perform(post(path("/categories")).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"EXPENSE\",\"name\":\"Gym\",\"icon\":\"dumbbell\",\"color\":\"series-2\"}")));
        create(bill("Gym", 150_000, 1, "2026-10-01", null).replace(emi, gym)).andExpect(status().isCreated());
        mvc.perform(delete(path("/categories/" + gym)).with(me))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("category-in-use"));
    }

    // ---------------------------------------------------------------- helpers

    private String path(String rest) {
        return "/api/core/spaces/" + space + rest;
    }

    private String account(String name) throws Exception {
        return id(mvc.perform(post(path("/accounts")).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"BANK\",\"name\":\"" + name + "\"}")));
    }

    private String bill(String name, long amount, int everyMonths, String firstDue, String endsOn) {
        return """
                {"kind":"BILL","name":"%s","amount":%d,"accountId":"%s","categoryId":"%s","toAccountId":null,
                 "everyMonths":%d,"firstDue":"%s","endsOn":%s}
                """.formatted(name, amount, bank, emi, everyMonths, firstDue, endsOn == null ? "null" : "\"" + endsOn + "\"");
    }

    private String saving(String name, long amount, int everyMonths, String firstDue) {
        return """
                {"kind":"SAVING","name":"%s","amount":%d,"accountId":"%s","categoryId":null,"toAccountId":"%s",
                 "everyMonths":%d,"firstDue":"%s"}
                """.formatted(name, amount, bank, investments, everyMonths, firstDue);
    }

    private ResultActions create(String body) throws Exception {
        return mvc.perform(post(path("/commitments")).with(me).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions pay(String id, String body) throws Exception {
        return mvc.perform(post(path("/commitments/" + id + "/payments")).with(me).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void expectPaid(String id, String date, boolean paid) throws Exception {
        mvc.perform(get(path("/commitments/due")).with(me).param("from", date).param("to", date))
                .andExpect(jsonPath("$[?(@.commitmentId == '" + id + "')].paid").value(paid));
    }

    private void expense(String description, long amount, String category, java.time.LocalDate on) throws Exception {
        mvc.perform(post(path("/transactions")).with(me).contentType(MediaType.APPLICATION_JSON).content("""
                {"kind":"EXPENSE","amount":%d,"accountId":"%s","categoryId":"%s","occurredOn":"%s","description":"%s"}
                """.formatted(amount, bank, category, on, description))).andExpect(status().isCreated());
    }

    private long commands() {
        return jdbc.sql("select count(*) from outbox_events where aggregate_id = ? and payload->>'entryType' = 'EXPENSE'")
                .param(UUID.fromString(space)).query(Long.class).single();
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
