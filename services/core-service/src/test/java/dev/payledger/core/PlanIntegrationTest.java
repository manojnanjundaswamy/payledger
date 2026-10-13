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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Free's limits, the 30-day trial and complimentary Premium (docs/plan/market-and-pricing.md). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "payledger.plans.complimentary=Team@PayLedger.example, beta@payledger.example")
@AutoConfigureMockMvc
class PlanIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    RequestPostProcessor me;
    String userId;
    String space;
    String categories;

    @BeforeEach
    void newUser() throws Exception {
        me = user(UUID.randomUUID() + "@example.com");
        String profile = mvc.perform(get("/api/core/me").with(me)).andReturn().getResponse().getContentAsString();
        userId = JsonPath.read(profile, "$.id");
        space = JsonPath.read(profile, "$.spaces[0].id");
        categories = mvc.perform(get(path("/categories")).with(me)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void aNewUserIsOnFreeWithTheTrialAvailable() throws Exception {
        account("HDFC");
        mvc.perform(get("/api/core/plan").with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("FREE")).andExpect(jsonPath("$.trialAvailable").value(true))
                .andExpect(jsonPath("$.limits.accounts").value(5)).andExpect(jsonPath("$.limits.budgets").value(5))
                .andExpect(jsonPath("$.limits.bills").value(5)).andExpect(jsonPath("$.limits.big-costs").value(1))
                .andExpect(jsonPath("$.limits.imports").value(3))
                .andExpect(jsonPath("$.usage.accounts").value(1));
    }

    @Test
    void freeStopsAddingPastALimitButNeverEditing() throws Exception {
        List<String> accounts = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            accounts.add(account("Account " + i).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        }
        account("Sixth").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan-limit"))
                .andExpect(jsonPath("$.detail").value("the Free plan allows 5 open accounts; Premium has no limit"));
        String first = JsonPath.read(accounts.getFirst(), "$.id");
        // Renaming is always allowed; archiving frees a place, and taking it back needs one.
        patchAccount(first, "{\"name\":\"Renamed\"}").andExpect(status().isOk());
        patchAccount(first, "{\"archived\":true}").andExpect(status().isOk());
        account("Sixth").andExpect(status().isCreated());
        patchAccount(first, "{\"archived\":false}").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan-limit"));

        List<String> names = List.of("Groceries", "Eating out", "Rent", "Utilities", "Fuel & transport", "Shopping");
        for (int i = 0; i < 5; i++) {
            budget(category(names.get(i)), 100_000).andExpect(status().isOk());
        }
        budget(category("Shopping"), 100_000).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan-limit"));
        budget(category("Groceries"), 200_000).andExpect(status().isOk());

        String bank = JsonPath.read(accounts.get(1), "$.id");
        for (int i = 1; i <= 5; i++) {
            bill(bank, "Bill " + i, 1).andExpect(status().isCreated());
        }
        bill(bank, "Bill 6", 1).andExpect(status().isForbidden());
        // Big yearly costs have their own limit of one.
        String insurance = JsonPath.read(bill(bank, "Insurance", 12).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        bill(bank, "School fees", 12).andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("1 big yearly")));
        mvc.perform(put(path("/commitments/" + insurance)).with(me).param("version", "1").contentType(MediaType.APPLICATION_JSON)
                        .content(billBody(bank, "Insurance premium", 12))).andExpect(status().isOk());
    }

    @Test
    void freeAllowsThreeImportsAMonthAndAnUndoneImportDoesntCount() throws Exception {
        account("HDFC savings");
        String undoMe = null;
        for (int i = 1; i <= 3; i++) {
            String id = JsonPath.read(importRow(i).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.importId");
            undoMe = undoMe == null ? id : undoMe;
        }
        importRow(4).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan-limit"));
        mvc.perform(delete(path("/imports/" + undoMe)).with(me)).andExpect(status().isNoContent());
        importRow(4).andExpect(status().isCreated());
    }

    @Test
    void theTrialIsThirtyDaysOnceAndWhenItEndsNothingIsLost() throws Exception {
        mvc.perform(post("/api/core/plan/trial").with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("PREMIUM")).andExpect(jsonPath("$.source").value("TRIAL"))
                .andExpect(jsonPath("$.trialAvailable").value(false)).andExpect(jsonPath("$.limits").doesNotExist());
        java.time.Instant until = java.time.Instant.parse(JsonPath.read(
                mvc.perform(get("/api/core/plan").with(me)).andReturn().getResponse().getContentAsString(), "$.premiumUntil"));
        assertThat(until).isBetween(java.time.Instant.now().plus(java.time.Duration.ofDays(29)), java.time.Instant.now().plus(java.time.Duration.ofDays(31)));
        mvc.perform(post("/api/core/plan/trial").with(me)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("trial-used"));

        for (int i = 1; i <= 7; i++) {
            account("Account " + i).andExpect(status().isCreated());
        }
        // The trial ends: all 7 accounts stay, but an 8th can't be added.
        jdbc.sql("update users set premium_until = now() - interval '1 minute' where id = ?").param(UUID.fromString(userId)).update();
        mvc.perform(get("/api/core/plan").with(me)).andExpect(jsonPath("$.plan").value("FREE")).andExpect(jsonPath("$.usage.accounts").value(7));
        mvc.perform(get(path("/accounts")).with(me)).andExpect(jsonPath("$.length()").value(7));
        account("Account 8").andExpect(status().isForbidden());
    }

    @Test
    void listedEmailsHaveComplimentaryPremium() throws Exception {
        var team = user("team@payledger.example");
        mvc.perform(get("/api/core/me").with(team)).andExpect(status().isOk());
        mvc.perform(get("/api/core/plan").with(team))
                .andExpect(jsonPath("$.plan").value("PREMIUM")).andExpect(jsonPath("$.source").value("COMPLIMENTARY"))
                .andExpect(jsonPath("$.premiumUntil").doesNotExist());
    }

    @Test
    void manyAddsAtOnceCantGoPastALimit() throws Exception {
        for (int i = 1; i <= 4; i++) {
            account("Account " + i);
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int n = i;
                results.add(pool.submit(() -> account("Racer " + n).andReturn().getResponse().getStatus()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsOnly(201, 403).containsOnlyOnce(201);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static RequestPostProcessor user(String email) {
        String subject = UUID.randomUUID().toString();
        return jwt().jwt(j -> j.subject(subject).claim("email", email).claim("email_verified", true));
    }

    private String path(String rest) {
        return "/api/core/spaces/" + space + rest;
    }

    private String category(String name) {
        return JsonPath.<List<String>>read(categories, "$[?(@.name == '" + name + "')].id").getFirst();
    }

    private ResultActions account(String name) throws Exception {
        return mvc.perform(post(path("/accounts")).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"BANK\",\"name\":\"" + name + "\"}"));
    }

    private ResultActions patchAccount(String id, String body) throws Exception {
        return mvc.perform(patch(path("/accounts/" + id)).with(me).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions budget(String categoryId, long amount) throws Exception {
        return mvc.perform(put(path("/budgets/" + categoryId)).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + amount + ",\"from\":\"2026-10\"}"));
    }

    private String billBody(String account, String name, int everyMonths) {
        return """
                {"kind":"BILL","name":"%s","amount":100000,"accountId":"%s","categoryId":"%s","everyMonths":%d,"firstDue":"2026-11-01"}
                """.formatted(name, account, category("Insurance"), everyMonths);
    }

    private ResultActions bill(String account, String name, int everyMonths) throws Exception {
        return mvc.perform(post(path("/commitments")).with(me).contentType(MediaType.APPLICATION_JSON).content(billBody(account, name, everyMonths)));
    }

    private ResultActions importRow(int n) throws Exception {
        String csv = "date,amount,type,account,category,description\\n0" + n + "-10-2026,100,expense,HDFC savings,Groceries,Row " + n + "\\n";
        return mvc.perform(post(path("/imports")).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileName\":\"f.csv\",\"csv\":\"" + csv + "\"}"));
    }
}
