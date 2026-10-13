package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CategoryIntegrationTest {

    @Autowired MockMvc mvc;

    RequestPostProcessor me;
    String space;
    String groceries;
    String salary;

    @BeforeEach
    void newUser() throws Exception {
        String subject = UUID.randomUUID().toString();
        me = jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
        space = JsonPath.read(mvc.perform(get("/api/core/me").with(me)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
        String categories = mvc.perform(get(path("/categories")).with(me)).andReturn().getResponse().getContentAsString();
        groceries = JsonPath.<List<String>>read(categories, "$[?(@.name == 'Groceries')].id").getFirst();
        salary = JsonPath.<List<String>>read(categories, "$[?(@.name == 'Salary')].id").getFirst();
    }

    @Test
    void addRenameAndRecolourACategory() throws Exception {
        String pets = id(create("EXPENSE", "  Pets ", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Pets")).andExpect(jsonPath("$.hidden").value(false)));

        replace(pets, "Pet care", "paw-print", "series-5", null, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Pet care")).andExpect(jsonPath("$.icon").value("paw-print"))
                .andExpect(jsonPath("$.color").value("series-5")).andExpect(jsonPath("$.kind").value("EXPENSE"));
        mvc.perform(get(path("/categories")).with(me)).andExpect(jsonPath("$.length()").value(21));
    }

    @Test
    void namesAreUniquePerKindIgnoringCase() throws Exception {
        create("EXPENSE", "groceries", null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("category-name-in-use"));
        // "Other" exists for both kinds, and an income can share an expense's name.
        create("INCOME", "Groceries", null).andExpect(status().isCreated());

        String pets = id(create("EXPENSE", "Pets", null));
        replace(pets, "RENT", "paw-print", "series-1", null, false)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("category-name-in-use"));
        // Changing only the case of its own name is fine.
        replace(pets, "PETS", "paw-print", "series-1", null, false).andExpect(status().isOk());
    }

    @Test
    void concurrentCreatesOfTheSameNameAddItOnce() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String name = i % 2 == 0 ? "Gym" : "gym";
                results.add(pool.submit(() -> create("EXPENSE", name, null).andReturn().getResponse().getStatus()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsOnly(201, 409).containsOnlyOnce(201);
        }
    }

    @Test
    void subcategoriesAreOneLevelDeepAndOfTheSameKind() throws Exception {
        String snacks = id(create("EXPENSE", "Snacks", groceries).andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentId").value(groceries)));

        create("EXPENSE", "Chips", snacks).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("category-parent-invalid"));
        create("INCOME", "Bonus", groceries).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("category-parent-invalid"));
        create("EXPENSE", "Orphan", UUID.randomUUID().toString()).andExpect(status().isUnprocessableContent());
        // Groceries has a subcategory, so it can't become one.
        String rent = JsonPath.<List<String>>read(list(), "$[?(@.name == 'Rent')].id").getFirst();
        replace(groceries, "Groceries", "shopping-basket", "series-1", rent, false)
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("category-parent-invalid"));
        replace(snacks, "Snacks", "cookie", "series-1", snacks, false).andExpect(status().isUnprocessableContent());

        // Moving it back to the top level.
        replace(snacks, "Snacks", "cookie", "series-1", null, false).andExpect(jsonPath("$.parentId").doesNotExist());
    }

    @Test
    void hidingACategoryHidesItsSubcategoriesAndStopsNewTransactions() throws Exception {
        String snacks = id(create("EXPENSE", "Snacks", groceries));
        String bank = createAccount();
        String old = id(createTransaction(bank, snacks).andExpect(status().isCreated()));

        replace(groceries, "Groceries", "shopping-basket", "series-1", null, true).andExpect(jsonPath("$.hidden").value(true));
        assertThat(JsonPath.<List<Boolean>>read(list(), "$[?(@.id == '" + snacks + "')].hidden")).containsExactly(true);
        createTransaction(bank, snacks).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("category-hidden"));
        // A subcategory can't be shown while its parent is hidden.
        replace(snacks, "Snacks", "cookie", "series-1", groceries, false)
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("category-parent-invalid"));
        // Old transactions keep their category and can still be edited.
        mvc.perform(put(path("/transactions/" + old)).with(me).contentType(MediaType.APPLICATION_JSON)
                        .content(txn(bank, snacks, "\"version\":1,")))
                .andExpect(status().isOk());

        replace(groceries, "Groceries", "shopping-basket", "series-1", null, false);
        assertThat(JsonPath.<List<Boolean>>read(list(), "$[?(@.id == '" + snacks + "')].hidden")).containsExactly(false);
        createTransaction(bank, snacks).andExpect(status().isCreated());
    }

    @Test
    void onlyANeverUsedCategoryCanBeDeleted() throws Exception {
        String unused = id(create("EXPENSE", "Unused", null));
        mvc.perform(delete(path("/categories/" + unused)).with(me)).andExpect(status().isNoContent());
        mvc.perform(delete(path("/categories/" + unused)).with(me))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("category-not-found"));

        create("EXPENSE", "Snacks", groceries);
        mvc.perform(delete(path("/categories/" + groceries)).with(me))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("category-has-subcategories"));

        // A deleted transaction still counts: its reversed entries stay in the ledger under this category.
        String once = id(create("INCOME", "Lottery", null));
        String bank = createAccount();
        String txn = id(mvc.perform(post(path("/transactions")).with(me).contentType(MediaType.APPLICATION_JSON)
                .content(txn(bank, once, "").replace("EXPENSE", "INCOME"))).andExpect(status().isCreated()));
        mvc.perform(delete(path("/transactions/" + txn)).with(me).param("version", "1")).andExpect(status().isNoContent());
        mvc.perform(delete(path("/categories/" + once)).with(me))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("category-in-use"));
    }

    @Test
    void badInputIsRefused() throws Exception {
        mvc.perform(post(path("/categories")).with(me).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"TRANSFER\",\"name\":\"X\",\"icon\":\"tag\",\"color\":\"series-1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path("/categories")).with(me).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"EXPENSE\",\"name\":\"X\",\"icon\":\"<script>\",\"color\":\"series-1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path("/categories")).with(me).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"EXPENSE\",\"name\":\"X\",\"icon\":\"tag\",\"color\":\"#ff0000\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path("/categories")).with(me).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"EXPENSE\",\"name\":\"   \",\"icon\":\"tag\",\"color\":\"series-1\"}"))
                .andExpect(status().isBadRequest());
        replace(UUID.randomUUID().toString(), "X", "tag", "series-1", null, false).andExpect(status().isNotFound());
    }

    @Test
    void otherPeoplesCategoriesAreOutOfReach() throws Exception {
        String stranger = UUID.randomUUID().toString();
        var them = jwt().jwt(j -> j.subject(stranger).claim("email", stranger + "@example.com").claim("email_verified", true));
        String theirSpace = JsonPath.read(mvc.perform(get("/api/core/me").with(them)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");

        // My category id, through their own space: not found, so it can't be renamed, deleted or used as a parent.
        String theirPath = "/api/core/spaces/" + theirSpace + "/categories/";
        mvc.perform(put(theirPath + groceries).with(them).contentType(MediaType.APPLICATION_JSON)
                        .content(body("X", "tag", "series-1", null, false, null)))
                .andExpect(status().isNotFound());
        mvc.perform(delete(theirPath + groceries).with(them)).andExpect(status().isNotFound());
        mvc.perform(post("/api/core/spaces/" + theirSpace + "/categories").with(them).contentType(MediaType.APPLICATION_JSON)
                        .content(body("X", "tag", "series-1", groceries, false, "EXPENSE")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(delete(path("/categories/" + groceries)).with(them)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("space-not-found"));
    }

    // ---------------------------------------------------------------- helpers

    private String path(String rest) {
        return "/api/core/spaces/" + space + rest;
    }

    private String list() throws Exception {
        return mvc.perform(get(path("/categories")).with(me)).andReturn().getResponse().getContentAsString();
    }

    private ResultActions create(String kind, String name, String parentId) throws Exception {
        return mvc.perform(post(path("/categories")).with(me).contentType(MediaType.APPLICATION_JSON)
                .content(body(name, "tag", "series-2", parentId, false, kind)));
    }

    private ResultActions replace(String id, String name, String icon, String color, String parentId, boolean hidden) throws Exception {
        return mvc.perform(put(path("/categories/" + id)).with(me).contentType(MediaType.APPLICATION_JSON)
                .content(body(name, icon, color, parentId, hidden, null)));
    }

    private static String body(String name, String icon, String color, String parentId, boolean hidden, String kind) {
        return """
                {"kind":%s,"name":"%s","icon":"%s","color":"%s","parentId":%s,"hidden":%s}
                """.formatted(quoted(kind), name, icon, color, quoted(parentId), hidden);
    }

    private String createAccount() throws Exception {
        return id(mvc.perform(post(path("/accounts")).with(me).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"BANK\",\"name\":\"HDFC\"}"))
                .andExpect(status().isCreated()));
    }

    private ResultActions createTransaction(String account, String category) throws Exception {
        return mvc.perform(post(path("/transactions")).with(me).contentType(MediaType.APPLICATION_JSON).content(txn(account, category, "")));
    }

    private static String txn(String account, String category, String extra) {
        return """
                {%s"kind":"EXPENSE","amount":5000,"accountId":"%s","categoryId":"%s","occurredOn":"2026-10-05","description":"Test"}
                """.formatted(extra, account, category);
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
