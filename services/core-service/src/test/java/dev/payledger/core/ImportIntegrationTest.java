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
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ImportIntegrationTest {

    static final String HEADER = "date,amount,type,account,category,subcategory,description,merchant,payment_mode,to_account,tags,notes,currency,external_id\n";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    RequestPostProcessor me;
    String space;
    String hdfc;

    @BeforeEach
    void newUser() throws Exception {
        String subject = UUID.randomUUID().toString();
        me = jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
        space = JsonPath.read(mvc.perform(get("/api/core/me").with(me)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
        hdfc = account("HDFC savings");
        account("Cash");
    }

    @Test
    void thePreviewChecksEveryRowAndSavesNothing() throws Exception {
        String csv = HEADER
                + "05-10-2026,486,expense,HDFC savings,Groceries,,BigBasket,,UPI,,,,INR,\n"
                + "01-10-2026,\"1,20,000\",income,hdfc savings,Salary,,October salary,,,,,,,\n"
                + "06-10-2026,2000,transfer,HDFC savings,,,ATM,,,Cash,,,,\n"
                + "31-02-2026,100,expense,HDFC savings,Groceries,,Bad date,,,,,,,\n"
                + "07-10-2026,12abc,expense,HDFC savings,Groceries,,Bad amount,,,,,,,\n"
                + "07-10-2026,100,spend,HDFC savings,Groceries,,Bad type,,,,,,,\n"
                + "08-10-2026,700,expense,ICICI card,Pets,,Vet,,,,,,,\n"
                + "09-10-2026,300,expense,ICICI card,Groceries,Snacks,Chips,,,,,,,\n"
                + "09-10-2026,300,expense,HDFC savings,Groceries,,In dollars,,,,,,USD,\n";

        preview(csv, Map.of(), Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(9)).andExpect(jsonPath("$.ready").value(3)).andExpect(jsonPath("$.errors").value(6))
                .andExpect(jsonPath("$.rows[0].line").value(2)).andExpect(jsonPath("$.rows[0].status").value("READY"))
                .andExpect(jsonPath("$.rows[0].amount").value(48_600)).andExpect(jsonPath("$.rows[0].kind").value("EXPENSE"))
                .andExpect(jsonPath("$.rows[1].amount").value(12_000_000))
                .andExpect(jsonPath("$.rows[2].kind").value("TRANSFER"))
                .andExpect(jsonPath("$.rows[3].problems[0].column").value("date"))
                .andExpect(jsonPath("$.rows[4].problems[0].column").value("amount"))
                .andExpect(jsonPath("$.rows[5].problems[0].column").value("type"))
                .andExpect(jsonPath("$.rows[6].problems[*].column").value(org.hamcrest.Matchers.contains("account", "category")))
                .andExpect(jsonPath("$.rows[8].problems[0].column").value("currency"))
                .andExpect(jsonPath("$.unknownAccounts[0].key").value("icici card"))
                .andExpect(jsonPath("$.unknownAccounts[0].rows").value(2))
                .andExpect(jsonPath("$.unknownCategories[*].key").value(org.hamcrest.Matchers.contains("EXPENSE:pets", "EXPENSE:groceries/snacks")));
        assertThat(transactionCount()).isZero();
    }

    @Test
    void importingAddsTheReadyRowsAndImportingAgainAddsNothing() throws Exception {
        String csv = HEADER
                + "05-10-2026,486,expense,HDFC savings,Groceries,,BigBasket,,UPI,,weekly;family,Milk too,INR,\n"
                + "01-10-2026,\"1,20,000\",income,HDFC savings,Salary,,,Acme,,,,,,REF-1\n"
                + "06-10-2026,2000,transfer,HDFC savings,,,ATM,,,Cash,,,,\n"
                + "07-10-2026,oops,expense,HDFC savings,Groceries,,Skipped,,,,,,,\n";

        String id = JsonPath.read(commit(csv, Map.of(), Map.of()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.imported").value(3)).andExpect(jsonPath("$.skipped").value(1))
                .andReturn().getResponse().getContentAsString(), "$.importId");
        assertThat(transactionCount()).isEqualTo(3);
        Map<String, Object> bigBasket = jdbc.sql("select description, note, tags::text as tags, import_id::text as import_id from transactions where space_id = ? and amount = 48600")
                .param(UUID.fromString(space)).query().singleRow();
        assertThat(bigBasket).containsEntry("description", "BigBasket").containsEntry("note", "Milk too")
                .containsEntry("tags", "{weekly,family,upi}").containsEntry("import_id", id);
        // The merchant stands in for a missing description; each import posts to the ledger like any transaction.
        assertThat(jdbc.sql("select count(*) from transactions where space_id = ? and description = 'Acme' and external_id = 'REF-1'")
                .param(UUID.fromString(space)).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from outbox_events where aggregate_id = ? and payload->>'commandType' = 'ledger.post'")
                .param(UUID.fromString(space)).query(Long.class).single()).isEqualTo(3);

        preview(csv, Map.of(), Map.of()).andExpect(jsonPath("$.ready").value(0)).andExpect(jsonPath("$.duplicates").value(3));
        commit(csv, Map.of(), Map.of()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("nothing-to-import"));
        // A changed description is a different row, but the same reference is the same transaction.
        preview(csv.replace("BigBasket", "BigBasket order").replace(",,Acme,", ",Salary!,Acme,"), Map.of(), Map.of())
                .andExpect(jsonPath("$.ready").value(1)).andExpect(jsonPath("$.duplicates").value(2));
    }

    @Test
    void unknownNamesCanBeMappedToExistingOnesOrCreated() throws Exception {
        String csv = HEADER
                + "08-10-2026,700,expense,ICICI card,Pets,,Vet,,,,,,,\n"
                + "09-10-2026,300,expense,ICICI card,Groceries,Snacks,Chips,,,,,,,\n"
                + "10-10-2026,250,expense,SBI,Groceries,,Veggies,,,,,,,\n";
        // Mapped names stay listed, so the screen keeps showing every choice while they are made.
        preview(csv, Map.of("icici card", "new:CARD", "sbi", hdfc), Map.of("EXPENSE:pets", "new", "EXPENSE:groceries/snacks", "new"))
                .andExpect(jsonPath("$.ready").value(3))
                .andExpect(jsonPath("$.unknownAccounts[*].key").value(org.hamcrest.Matchers.contains("icici card", "sbi")))
                .andExpect(jsonPath("$.unknownCategories.length()").value(2));
        commit(csv, Map.of("icici card", "new:CARD", "sbi", hdfc), Map.of("EXPENSE:pets", "new", "EXPENSE:groceries/snacks", "new"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.imported").value(3));

        mvc.perform(get(path("/accounts")).with(me))
                .andExpect(jsonPath("$[?(@.name == 'ICICI card')].kind").value("CARD"))
                .andExpect(jsonPath("$[?(@.name == 'SBI')]").isEmpty());
        String categories = mvc.perform(get(path("/categories")).with(me)).andReturn().getResponse().getContentAsString();
        String groceries = JsonPath.<List<String>>read(categories, "$[?(@.name == 'Groceries')].id").getFirst();
        assertThat(JsonPath.<List<String>>read(categories, "$[?(@.name == 'Snacks')].parentId")).containsExactly(groceries);
        assertThat(JsonPath.<List<String>>read(categories, "$[?(@.name == 'Pets')].kind")).containsExactly("EXPENSE");
        assertThat(jdbc.sql("select count(*) from transactions where space_id = ? and account_id = ?")
                .param(UUID.fromString(space)).param(UUID.fromString(hdfc)).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aBankStatementWithDebitAndCreditColumnsImportsIntoOther() throws Exception {
        String csv = "Txn Date,Narration,Withdrawal Amount,Deposit Amount,Ref,Account\n"
                + "05/10/26,UPI-SWIGGY,\"1,250.50\",,UTR001,HDFC savings\n"
                + "05-Oct-2026,NEFT-ACME SALARY,,\"1,00,000\",UTR002,HDFC savings\n";
        preview(csv, Map.of(), Map.of())
                .andExpect(jsonPath("$.ready").value(2))
                .andExpect(jsonPath("$.rows[0].kind").value("EXPENSE")).andExpect(jsonPath("$.rows[0].amount").value(125_050))
                .andExpect(jsonPath("$.rows[0].category").value("Other")).andExpect(jsonPath("$.rows[0].description").value("UPI-SWIGGY"))
                .andExpect(jsonPath("$.rows[1].kind").value("INCOME")).andExpect(jsonPath("$.rows[1].amount").value(10_000_000))
                .andExpect(jsonPath("$.rows[1].date").value("2026-10-05"));
    }

    @Test
    void undoingAnImportDeletesItsTransactionsAndItCanBeImportedAgain() throws Exception {
        String csv = HEADER + "05-10-2026,486,expense,HDFC savings,Groceries,,BigBasket,,,,,,,REF-9\n";
        String id = JsonPath.read(commit(csv, Map.of(), Map.of()).andReturn().getResponse().getContentAsString(), "$.importId");

        mvc.perform(delete(path("/imports/" + id)).with(me)).andExpect(status().isNoContent());
        assertThat(transactionCount()).isZero();
        assertThat(jdbc.sql("select count(*) from outbox_events where aggregate_id = ? and payload->>'commandType' = 'ledger.reverse'")
                .param(UUID.fromString(space)).query(Long.class).single()).isEqualTo(1);
        mvc.perform(get(path("/imports")).with(me))
                .andExpect(jsonPath("$[0].fileName").value("october.csv")).andExpect(jsonPath("$[0].undone").value(true));
        mvc.perform(delete(path("/imports/" + id)).with(me)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("import-undone"));

        commit(csv, Map.of(), Map.of()).andExpect(status().isCreated()).andExpect(jsonPath("$.imported").value(1));
    }

    @Test
    void aFullFileOf2000RowsImportsInOneGo() throws Exception {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 0; i < 2_000; i++) {
            csv.append("05-10-2026,").append(100 + i).append(",expense,HDFC savings,Groceries,,Row ").append(i).append(",,,,,,,\n");
        }
        // The gateway gives up after 15 seconds; on a laptop this takes about 6.
        commit(csv.toString(), Map.of(), Map.of()).andExpect(status().isCreated()).andExpect(jsonPath("$.imported").value(2_000));
        assertThat(transactionCount()).isEqualTo(2_000);
    }

    @Test
    void filesThatCantBeImportedAreRefusedWithAReason() throws Exception {
        preview("amount,account\n100,HDFC savings\n", Map.of(), Map.of()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("import-columns-missing"));
        preview(HEADER, Map.of(), Map.of()).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("empty-import"));
        preview(HEADER + "05-10-2026,1,expense,HDFC savings,Groceries,,x,,,,,,,\n".repeat(2_001), Map.of(), Map.of())
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("import-too-large"));

        String stranger = UUID.randomUUID().toString();
        var them = jwt().jwt(j -> j.subject(stranger).claim("email", stranger + "@example.com").claim("email_verified", true));
        mvc.perform(get(path("/imports")).with(them)).andExpect(status().isNotFound());
    }

    @Test
    void theTemplateUsesYourOwnAccounts() throws Exception {
        mvc.perform(get(path("/imports/template.csv")).with(me))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("date,amount,type,account,category")))
                .andExpect(content().string(containsString(",HDFC savings,Groceries,")))
                .andExpect(content().string(containsString(",Cash,")));
    }

    // ---------------------------------------------------------------- helpers

    private String path(String rest) {
        return "/api/core/spaces/" + space + rest;
    }

    private String account(String name) throws Exception {
        return JsonPath.read(mvc.perform(post(path("/accounts")).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"BANK\",\"name\":\"" + name + "\"}")).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private ResultActions preview(String csv, Map<String, String> accounts, Map<String, String> categories) throws Exception {
        return mvc.perform(post(path("/imports/preview")).with(me).contentType(MediaType.APPLICATION_JSON).content(body(csv, accounts, categories)));
    }

    private ResultActions commit(String csv, Map<String, String> accounts, Map<String, String> categories) throws Exception {
        return mvc.perform(post(path("/imports")).with(me).contentType(MediaType.APPLICATION_JSON).content(body(csv, accounts, categories)));
    }

    private static String body(String csv, Map<String, String> accounts, Map<String, String> categories) {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        return json.writeValueAsString(Map.of("fileName", "october.csv", "csv", csv, "accounts", accounts, "categories", categories));
    }

    private long transactionCount() {
        return jdbc.sql("select count(*) from transactions where space_id = ? and deleted_at is null")
                .param(UUID.fromString(space)).query(Long.class).single();
    }
}
