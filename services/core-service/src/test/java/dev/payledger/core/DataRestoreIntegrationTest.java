package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
import dev.payledger.core.money.DataFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Export and import stay in step: what one account exports, a fresh account imports, and exports again the same
 * (ids aside). And every table holding someone's data is either in the export or left out on purpose.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DataRestoreIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    @Test
    void everyTableWithSomeonesDataIsExportedOrLeftOutOnPurpose() throws Exception {
        List<String> tables = jdbc.sql("""
                        select distinct table_name from information_schema.columns where table_schema = 'public'
                          and column_name in ('user_id', 'space_id', 'created_by', 'group_id', 'goal_id', 'commitment_id', 'expense_id')
                          and table_name not like 'flyway%'
                        """).query(String.class).list();
        assertThat(DataFormat.TABLES.keySet()).as("tables with someone's data must be declared in DataFormat.TABLES").containsAll(tables);

        // And what TABLES says is exported, the export has (and nothing it doesn't know about).
        var me = user();
        JsonNode export = json.readTree(body(get("/api/core/me/export"), me, null));
        Set<String> sections = new TreeSet<>();
        export.propertyNames().forEach(sections::add);
        sections.removeAll(Set.of("format", "version", "personalSpaceId", "exportedAt"));
        Set<String> declared = new TreeSet<>();
        DataFormat.TABLES.values().stream().filter(v -> !v.startsWith("-")).forEach(declared::add);
        assertThat(sections).isEqualTo(declared);
        assertThat(DataFormat.RESTORED).allMatch(sections::contains);
    }

    @Test
    void whatOneAccountExportsAFreshAccountRestoresTheSame() throws Exception {
        var asha = user();
        String space = JsonPath.read(body(get("/api/core/me"), asha, null), "$.spaces[0].id");
        String base = "/api/core/spaces/" + space;
        body(patch("/api/core/me"), asha, "{\"budgetStartDay\":25,\"locale\":\"en-IN\"}");
        String hdfc = id(body(post(base + "/accounts"), asha, "{\"kind\":\"BANK\",\"name\":\"HDFC\",\"openingBalance\":5000000,\"openingDate\":\"2026-01-01\"}"));
        String card = id(body(post(base + "/accounts"), asha, "{\"kind\":\"CARD\",\"name\":\"ICICI card\",\"openingBalance\":120000,\"openingDate\":\"2026-01-01\"}"));
        String food = JsonPath.<List<String>>read(body(get(base + "/categories"), asha, null), "$[?(@.name == 'Groceries')].id").getFirst();
        String salary = JsonPath.<List<String>>read(body(get(base + "/categories"), asha, null), "$[?(@.kind == 'INCOME')].id").getFirst();
        String pets = id(body(post(base + "/categories"), asha, "{\"kind\":\"EXPENSE\",\"name\":\"Pets\",\"icon\":\"paw-print\",\"color\":\"series-3\"}"));
        String vet = id(body(post(base + "/categories"), asha, "{\"kind\":\"EXPENSE\",\"name\":\"Vet\",\"icon\":\"heart\",\"color\":\"series-3\",\"parentId\":\"" + pets + "\"}"));
        String goal = id(body(post(base + "/goals"), asha, "{\"name\":\"Goa\",\"emoji\":\"🏖️\",\"target\":5000000,\"targetDate\":\"2027-03-31\"}"));
        body(post(base + "/goals/" + goal + "/contributions"), asha, "{\"amount\":100000,\"note\":\"Bonus\"}");
        body(post(base + "/transactions"), asha, txn("EXPENSE", 45000, hdfc, null, food, "BigBasket", "\"weekly\"", null, null));
        body(post(base + "/transactions"), asha, txn("EXPENSE", 120000, card, null, vet, "Dr Rao", "", goal, null));
        body(post(base + "/transactions"), asha, txn("INCOME", 9000000, hdfc, null, salary, "Salary", "", null, "Acme"));
        body(post(base + "/transactions"), asha, txn("TRANSFER", 120000, hdfc, card, null, "Card bill", "", null, null));
        body(put(base + "/budgets/" + food), asha, "{\"amount\":1200000,\"month\":\"2026-10\",\"rollover\":true}");
        String bill = id(body(post(base + "/commitments"), asha, """
                {"kind":"BILL","name":"Netflix","amount":64900,"accountId":"%s","categoryId":"%s","everyMonths":1,"firstDue":"2026-10-05","auto":true}
                """.formatted(card, pets)));
        body(post(base + "/commitments/" + bill + "/payments"), asha, "{\"dueDate\":\"2026-10-05\"}");
        body(post(base + "/category-rules"), asha, "{\"match\":\"bigbasket\",\"categoryId\":\"" + food + "\"}");
        body(put(base + "/tax/mappings/" + vet), asha, "{\"section\":\"80D\"}");
        String milk = JsonPath.read(body(post(base + "/daily-services"), asha, """
                {"name":"Milk","emoji":"🥛","rate":6000,"per":"DAY","accountId":"%s","startedOn":"2026-09-01"}
                """.formatted(hdfc)), "$.service.id");
        body(put(base + "/daily-services/" + milk + "/days/2026-09-03"), asha, "{\"quantity\":0}");
        body(put(base + "/daily-services/" + milk + "/days/2026-09-04"), asha, "{\"quantity\":2}");
        body(post(base + "/daily-services/" + milk + "/payments"), asha, "{\"month\":\"2026-09\"}");

        byte[] file = mvc.perform(get("/api/core/me/export").with(asha)).andReturn().getResponse().getContentAsByteArray();
        JsonNode before = json.readTree(file);
        assertThat(before.path("format").asString()).isEqualTo(DataFormat.NAME);

        // Into a fresh account.
        var again = user();
        String restored = mvc.perform(multipart("/api/core/me/import").file(new MockMultipartFile("file", "export.json", "application/json", file)).with(again))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(restored, "$.counts.transactions")).isEqualTo(6);  // the four above, the paid bill and the milk
        JsonNode after = json.readTree(mvc.perform(get("/api/core/me/export").with(again)).andReturn().getResponse().getContentAsByteArray());
        for (String section : DataFormat.RESTORED) {
            assertThat(normal(after, section)).as(section).isEqualTo(normal(before, section));
        }
        // Balances come from the ledger, so the restored accounts add up the same.
        String againSpace = JsonPath.read(body(get("/api/core/me"), again, null), "$.spaces[0].id");
        assertThat(JsonPath.<List<Object>>read(body(get("/api/core/spaces/" + againSpace + "/accounts"), again, null), "$[*].name")).contains("HDFC", "ICICI card");

        // Only once: an account with money in it already is refused.
        mvc.perform(multipart("/api/core/me/import").file(new MockMultipartFile("file", "export.json", "application/json", file)).with(again))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("import-not-empty"));
        mvc.perform(multipart("/api/core/me/import").file(new MockMultipartFile("file", "x.json", "application/json", "{\"hello\":1}".getBytes())).with(user()))
                .andExpect(jsonPath("$.code").value("import-unreadable"));
    }

    /** A section's rows as comparable text: ids replaced by what they point to (names), times and ids left out. */
    private List<String> normal(JsonNode export, String section) {
        Map<String, String> names = new HashMap<>();
        for (String s : List.of("accounts", "categories", "goals", "billsAndSavings", "dailyServices")) {
            export.path(s).forEach(r -> names.put(r.path("id").asString(), r.path("name").asString()));
        }
        export.path("transactions").forEach(r -> names.put(r.path("id").asString(), r.path("description").asString()));
        Function<JsonNode, String> n = (v) -> v == null || v.isNull() || v.isMissingNode() ? "-" : names.getOrDefault(v.asString(), v.toString());
        Map<String, Function<JsonNode, String>> rows = Map.ofEntries(
                Map.entry("profile", r -> r.path("budget_start_day") + "|" + r.path("locale") + "|" + r.path("currency")),
                Map.entry("categories", r -> r.path("kind") + "|" + r.path("name") + "|" + r.path("icon") + "|" + r.path("color") + "|" + n.apply(r.path("parent_id")) + "|" + r.path("hidden")),
                Map.entry("accounts", r -> r.path("kind") + "|" + r.path("name") + "|" + r.path("opening_balance") + "|" + r.path("opening_date")),
                Map.entry("goals", r -> r.path("name") + "|" + r.path("emoji") + "|" + r.path("target") + "|" + r.path("target_date")),
                Map.entry("transactions", r -> r.path("kind") + "|" + r.path("amount") + "|" + n.apply(r.path("account_id")) + "|" + n.apply(r.path("to_account_id"))
                        + "|" + n.apply(r.path("category_id")) + "|" + r.path("occurred_on") + "|" + r.path("description") + "|" + r.path("tags")
                        + "|" + n.apply(r.path("goal_id")) + "|" + r.path("counterparty")),
                Map.entry("budgets", r -> n.apply(r.path("category_id")) + "|" + r.path("effective_month") + "|" + r.path("amount") + "|" + r.path("rollover")),
                Map.entry("billsAndSavings", r -> r.path("kind") + "|" + r.path("name") + "|" + r.path("amount") + "|" + n.apply(r.path("account_id"))
                        + "|" + n.apply(r.path("category_id")) + "|" + r.path("every_months") + "|" + r.path("first_due") + "|" + r.path("auto")),
                Map.entry("billPayments", r -> n.apply(r.path("commitment_id")) + "|" + r.path("due_date") + "|" + n.apply(r.path("transaction_id"))),
                Map.entry("goalContributions", r -> n.apply(r.path("goal_id")) + "|" + r.path("amount") + "|" + r.path("note")),
                Map.entry("categoryRules", r -> r.path("match") + "|" + n.apply(r.path("category_id"))),
                Map.entry("taxMappings", r -> n.apply(r.path("category_id")) + "|" + r.path("section")),
                Map.entry("dailyServices", r -> r.path("name") + "|" + r.path("emoji") + "|" + r.path("rate") + "|" + r.path("per") + "|" + r.path("paid_leaves")
                        + "|" + n.apply(r.path("account_id")) + "|" + n.apply(r.path("category_id")) + "|" + r.path("started_on")),
                Map.entry("dailyServiceDays", r -> n.apply(r.path("service_id")) + "|" + r.path("day") + "|" + r.path("quantity")),
                Map.entry("dailyServicePayments", r -> n.apply(r.path("service_id")) + "|" + r.path("month") + "|" + n.apply(r.path("transaction_id")) + "|" + r.path("amount")));
        assertThat(rows.keySet()).as("every restored section is compared").containsExactlyInAnyOrderElementsOf(DataFormat.RESTORED);
        List<String> out = new ArrayList<>();
        export.path(section).forEach(r -> out.add(rows.get(section).apply(r)));
        out.sort(String::compareTo);
        return out;
    }

    private static String txn(String kind, long amount, String account, String to, String category, String description, String tags, String goal, String who) {
        return """
                {"kind":"%s","amount":%d,"accountId":"%s","toAccountId":%s,"categoryId":%s,"occurredOn":"2026-10-03","description":"%s","tags":[%s],"goalId":%s,"counterparty":%s}
                """.formatted(kind, amount, account, to == null ? "null" : "\"" + to + "\"", category == null ? "null" : "\"" + category + "\"", description, tags,
                goal == null ? "null" : "\"" + goal + "\"", who == null ? "null" : "\"" + who + "\"");
    }

    private static String id(String body) {
        return JsonPath.read(body, "$.id");
    }

    private String body(MockHttpServletRequestBuilder request, RequestPostProcessor who, String jsonBody) throws Exception {
        if (jsonBody != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(jsonBody);
        }
        return mvc.perform(request.with(who)).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private static RequestPostProcessor user() {
        String subject = UUID.randomUUID().toString();
        return jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
    }
}
