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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Smart categories: guesses from your history, rules that win over them, applying a rule to the past, and imports. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CategoryRuleIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    RequestPostProcessor me;
    String space;
    String bank;
    Map<String, String> category;

    @BeforeEach
    void newUser() throws Exception {
        String subject = UUID.randomUUID().toString();
        me = jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
        space = JsonPath.read(mvc.perform(get("/api/core/me").with(me)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
        String categories = mvc.perform(get(path("/categories")).with(me)).andReturn().getResponse().getContentAsString();
        category = Map.of(
                "Eating out", JsonPath.<List<String>>read(categories, "$[?(@.name == 'Eating out')].id").getFirst(),
                "Groceries", JsonPath.<List<String>>read(categories, "$[?(@.name == 'Groceries')].id").getFirst(),
                "Salary", JsonPath.<List<String>>read(categories, "$[?(@.name == 'Salary')].id").getFirst());
        bank = JsonPath.read(mvc.perform(post(path("/accounts")).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"BANK\",\"name\":\"HDFC\",\"openingBalance\":0,\"openingDate\":\"2026-01-01\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void historyGuessesRulesWinAndARuleAppliesToThePast() throws Exception {
        nothingGuessed("Swiggy");
        String first = expense("Swiggy", "Eating out");
        expense("swiggy ", "Eating out");
        expense("Swiggy", "Groceries");

        // What you chose most for this description, ignoring case and spaces.
        guess("EXPENSE", "SWIGGY").andExpect(jsonPath("$.source").value("HISTORY"))
                .andExpect(jsonPath("$.categoryId").value(category.get("Eating out"))).andExpect(jsonPath("$.times").value(2));
        // A longer description starting with the same word.
        guess("EXPENSE", "Swiggy Instamart order 4411").andExpect(jsonPath("$.source").value("SIMILAR"));
        // Income is guessed from income only.
        nothingGuessed("Swiggy", "INCOME");

        // A rule beats history, and the longest matching rule wins.
        String rule = id(mvc.perform(post(path("/category-rules")).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"match\":\"  Instamart \",\"categoryId\":\"" + category.get("Groceries") + "\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.match").value("instamart")));
        mvc.perform(post(path("/category-rules")).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"match\":\"swiggy\",\"categoryId\":\"" + category.get("Eating out") + "\"}")).andExpect(status().isOk());
        guess("EXPENSE", "Swiggy Instamart order 4411").andExpect(jsonPath("$.source").value("RULE"))
                .andExpect(jsonPath("$.categoryId").value(category.get("Groceries"))).andExpect(jsonPath("$.ruleId").value(rule));
        // Saving the same words again points the rule elsewhere instead of adding another.
        mvc.perform(post(path("/category-rules")).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"match\":\"INSTAMART\",\"categoryId\":\"" + category.get("Groceries") + "\"}")).andExpect(jsonPath("$.id").value(rule));
        mvc.perform(get(path("/category-rules")).with(me)).andExpect(jsonPath("$.length()").value(2));

        // Applying "swiggy" to the past moves the one that went to Groceries; each move goes through the ledger.
        long before = commands();
        String swiggyRule = JsonPath.<List<String>>read(mvc.perform(get(path("/category-rules")).with(me)).andReturn().getResponse()
                .getContentAsString(), "$[?(@.match == 'swiggy')].id").getFirst();
        mvc.perform(post(path("/category-rules/" + swiggyRule + "/apply")).with(me)).andExpect(jsonPath("$.applied").value(1));
        assertThat(commands() - before).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from transactions where space_id = ? and category_id = ?::uuid")
                .param(UUID.fromString(space)).param(category.get("Eating out")).query(Long.class).single()).isEqualTo(3);
        mvc.perform(get(path("/transactions/" + first)).with(me)).andExpect(jsonPath("$.version").value(1));

        // Bad words, someone else's rule.
        mvc.perform(post(path("/category-rules")).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"match\":\"   x  \",\"categoryId\":\"" + category.get("Groceries") + "\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-rule"));
        mvc.perform(delete(path("/category-rules/" + rule)).with(me)).andExpect(status().isNoContent());
        mvc.perform(delete(path("/category-rules/" + rule)).with(me)).andExpect(status().isNotFound());
    }

    @Test
    void anImportedRowWithoutACategoryGetsTheGuess() throws Exception {
        mvc.perform(post(path("/category-rules")).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"match\":\"zomato\",\"categoryId\":\"" + category.get("Eating out") + "\"}")).andExpect(status().isOk());
        String csv = "date,amount,type,account,category,subcategory,description,merchant,payment_mode,to_account,tags,notes,currency,external_id\n"
                + "05-10-2026,350,expense,HDFC,,,ZOMATO ORDER 991,,UPI,,,,INR,\n"
                + "06-10-2026,120,expense,HDFC,,,Something new,,UPI,,,,INR,\n";
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        mvc.perform(post(path("/imports")).with(me).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fileName", "oct.csv", "csv", csv, "accounts", Map.of(), "categories", Map.of()))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.imported").value(2));
        assertThat(jdbc.sql("select c.name from transactions t join categories c on c.id = t.category_id where t.space_id = ? order by t.occurred_on")
                .param(UUID.fromString(space)).query(String.class).list()).containsExactly("Eating out", "Other");
    }

    private String expense(String description, String categoryName) throws Exception {
        return id(mvc.perform(post(path("/transactions")).with(me).contentType(MediaType.APPLICATION_JSON).content("""
                {"kind":"EXPENSE","amount":10000,"accountId":"%s","categoryId":"%s","occurredOn":"%s","description":"%s"}
                """.formatted(bank, category.get(categoryName), java.time.LocalDate.now().minusDays(1), description))).andExpect(status().isCreated()));
    }

    private ResultActions guess(String kind, String description) throws Exception {
        return mvc.perform(get(path("/categories/guess")).with(me).param("kind", kind).param("description", description)).andExpect(status().isOk());
    }

    private void nothingGuessed(String description, String... kind) throws Exception {
        mvc.perform(get(path("/categories/guess")).with(me).param("kind", kind.length > 0 ? kind[0] : "EXPENSE").param("description", description))
                .andExpect(status().isNoContent());
    }

    private long commands() {
        return jdbc.sql("select count(*) from outbox_events where aggregate_id = ?").param(UUID.fromString(space)).query(Long.class).single();
    }

    private String path(String rest) {
        return "/api/core/spaces/" + space + rest;
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
