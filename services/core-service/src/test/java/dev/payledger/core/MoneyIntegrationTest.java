package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MoneyIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ConsumerFactory<String, String> consumerFactory;

    RequestPostProcessor me;
    String space;
    String groceries;
    String salary;

    @BeforeEach
    void newUser() throws Exception {
        String subject = UUID.randomUUID().toString();
        me = jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true).claim("name", "Priya"));
        space = JsonPath.read(mvc.perform(get("/api/core/me").with(me)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
        String categories = mvc.perform(get(path("/categories")).with(me)).andReturn().getResponse().getContentAsString();
        groceries = JsonPath.<List<String>>read(categories, "$[?(@.name == 'Groceries')].id").getFirst();
        salary = JsonPath.<List<String>>read(categories, "$[?(@.name == 'Salary')].id").getFirst();
    }

    @Test
    void aNewSpaceHasTheDefaultCategories() throws Exception {
        mvc.perform(get(path("/categories")).with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(20))
                .andExpect(jsonPath("$[?(@.kind == 'EXPENSE')]", org.hamcrest.Matchers.hasSize(15)))
                .andExpect(jsonPath("$[?(@.name == 'Eating out')].icon").value("utensils"));
    }

    @Test
    void openingBalancesArePostedToTheLedgerWithTheRightSigns() throws Exception {
        String bank = createAccount("BANK", "HDFC savings", 5_000_000);
        String card = createAccount("CARD", "ICICI Amazon Pay", 1_250_000);
        createAccount("CASH", "Wallet cash", 0);

        assertThat(commands()).hasSize(2);
        assertThat(legs(0)).containsExactly(
                leg("account:" + bank, "ASSET", 5_000_000), leg("equity:opening-balances", "EQUITY", -5_000_000));
        // A card's opening balance is what you owe: a credit (negative) on a liability.
        assertThat(legs(1)).containsExactly(
                leg("account:" + card, "LIABILITY", -1_250_000), leg("equity:opening-balances", "EQUITY", 1_250_000));
        assertThat(command(0)).containsEntry("bookRef", "space:" + space).containsEntry("bookKind", "PERSONAL")
                .containsEntry("idempotencyKey", "account:" + bank + ":opening:v1").containsEntry("entryType", "OPENING_BALANCE");

        // Changing the opening balance reverses the old entry and posts the new one.
        mvc.perform(patch(path("/accounts/" + bank)).with(me).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"openingBalance\":6000000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance").value(6_000_000));
        assertThat(commands()).hasSize(4);
        assertThat(command(2)).containsEntry("commandType", "ledger.reverse")
                .containsEntry("reversesIdempotencyKey", "account:" + bank + ":opening:v1");
        assertThat(command(3)).containsEntry("idempotencyKey", "account:" + bank + ":opening:v2");

        // A rename doesn't touch the ledger.
        mvc.perform(patch(path("/accounts/" + bank)).with(me).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"HDFC\"}"))
                .andExpect(jsonPath("$.name").value("HDFC"));
        assertThat(commands()).hasSize(4);
    }

    @Test
    void expensesIncomeAndTransfersPostBalancedEntries() throws Exception {
        String bank = createAccount("BANK", "HDFC", 0);
        String card = createAccount("CARD", "ICICI", 0);

        String expense = id(createTransaction(fields("EXPENSE", 48_600, card, null, groceries, "BigBasket")).andExpect(status().isCreated()));
        createTransaction(fields("INCOME", 12_000_000, bank, null, salary, "Salary: Acme")).andExpect(status().isCreated());
        createTransaction(fields("TRANSFER", 48_600, bank, card, null, "Card bill")).andExpect(status().isCreated());

        assertThat(legs(0)).containsExactly(leg("category:" + groceries, "EXPENSE", 48_600), leg("account:" + card, "LIABILITY", -48_600));
        assertThat(legs(1)).containsExactly(leg("account:" + bank, "ASSET", 12_000_000), leg("category:" + salary, "INCOME", -12_000_000));
        // Paying the card bill: the card's debt goes down (debit), the bank goes down (credit).
        assertThat(legs(2)).containsExactly(leg("account:" + card, "LIABILITY", 48_600), leg("account:" + bank, "ASSET", -48_600));
        assertThat(command(0)).containsEntry("idempotencyKey", "txn:" + expense + ":v1").containsEntry("sourceRef", "txn:" + expense)
                .containsEntry("entryType", "EXPENSE").containsEntry("effectiveDate", "2026-10-05");
    }

    @Test
    void invalidTransactionsAreRefusedWithAStableCode() throws Exception {
        String bank = createAccount("BANK", "HDFC", 0);
        createTransaction(fields("EXPENSE", 100, bank, null, salary, "Wrong kind"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("category-kind-mismatch"));
        createTransaction(fields("TRANSFER", 100, bank, bank, null, "To itself"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-transfer"));
        createTransaction(fields("EXPENSE", 100, bank, null, null, "No category"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-expense"));
        createTransaction(fields("EXPENSE", 0, bank, null, groceries, "Zero")).andExpect(status().isBadRequest());
        createTransaction(fields("EXPENSE", 100, UUID.randomUUID().toString(), null, groceries, "No account"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("account-not-found"));

        mvc.perform(patch(path("/accounts/" + bank)).with(me).contentType(MediaType.APPLICATION_JSON).content("{\"archived\":true}"))
                .andExpect(jsonPath("$.archived").value(true));
        createTransaction(fields("EXPENSE", 100, bank, null, groceries, "Archived"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("account-archived"));
        assertThat(commands()).isEmpty();
    }

    @Test
    void otherPeoplesSpacesAreInvisible() throws Exception {
        String bank = createAccount("BANK", "HDFC", 0);
        String stranger = UUID.randomUUID().toString();
        var them = jwt().jwt(j -> j.subject(stranger).claim("email", stranger + "@example.com").claim("email_verified", true));

        mvc.perform(get(path("/accounts")).with(them)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("space-not-found"));
        mvc.perform(post(path("/transactions")).with(them).contentType(MediaType.APPLICATION_JSON)
                        .content(fields("EXPENSE", 100, bank, null, groceries, "Sneaky")))
                .andExpect(status().isNotFound());
    }

    @Test
    void editingMoneyReversesAndRepostsButEditingTheNoteDoesNot() throws Exception {
        String bank = createAccount("BANK", "HDFC", 0);
        String id = id(createTransaction(fields("EXPENSE", 250_000, bank, null, groceries, "Dinner")));

        replace(id, 1, fields("EXPENSE", 180_000, bank, null, groceries, "Dinner")).andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(180_000)).andExpect(jsonPath("$.version").value(2));
        assertThat(commands()).hasSize(3);
        assertThat(command(1)).containsEntry("commandType", "ledger.reverse")
                .containsEntry("idempotencyKey", "txn:" + id + ":v1:reversal").containsEntry("reversesIdempotencyKey", "txn:" + id + ":v1");
        assertThat(command(2)).containsEntry("idempotencyKey", "txn:" + id + ":v2");

        // Only words changed: version 3, but the v2 ledger entry stays live.
        replace(id, 2, fields("EXPENSE", 180_000, bank, null, groceries, "Dinner with Rahul")).andExpect(jsonPath("$.version").value(3));
        assertThat(commands()).hasSize(3);

        // The next money edit reverses v2 (the live one), not v3.
        replace(id, 3, fields("EXPENSE", 200_000, bank, null, groceries, "Dinner with Rahul")).andExpect(status().isOk());
        assertThat(command(3)).containsEntry("reversesIdempotencyKey", "txn:" + id + ":v2");
        assertThat(command(4)).containsEntry("idempotencyKey", "txn:" + id + ":v4");

        replace(id, 1, fields("EXPENSE", 1, bank, null, groceries, "Stale")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("version-conflict"));

        mvc.perform(delete(path("/transactions/" + id)).with(me).param("version", "4")).andExpect(status().isNoContent());
        assertThat(command(5)).containsEntry("reversesIdempotencyKey", "txn:" + id + ":v4");
        mvc.perform(get(path("/transactions/" + id)).with(me)).andExpect(status().isNotFound());
        mvc.perform(get(path("/transactions")).with(me)).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void retryingACreateWithTheSameClientIdAddsItOnce() throws Exception {
        String bank = createAccount("BANK", "HDFC", 0);
        String clientId = UUID.randomUUID().toString();
        String body = withId(clientId, fields("EXPENSE", 31_200, bank, null, groceries, "Milk"));

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> mvc.perform(post(path("/transactions")).with(me)
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsOnly(200, 201).containsOnlyOnce(201);
        }
        assertThat(jdbc.sql("select count(*) from transactions where id = ?").param(UUID.fromString(clientId)).query(Long.class).single()).isEqualTo(1);
        assertThat(commands()).hasSize(1);
    }

    @Test
    void concurrentEditsOfTheSameVersionLetExactlyOneWin() throws Exception {
        String bank = createAccount("BANK", "HDFC", 0);
        String id = id(createTransaction(fields("EXPENSE", 10_000, bank, null, groceries, "Race")));

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                long amount = 20_000 + i;
                results.add(pool.submit(() -> replace(id, 1, fields("EXPENSE", amount, bank, null, groceries, "Race"))
                        .andReturn().getResponse().getStatus()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsOnly(200, 409).containsOnlyOnce(200);
        }
        // One post for the create, then exactly one reversal + one post for the winning edit.
        assertThat(commands()).hasSize(3);
    }

    @Test
    void theListIsNewestFirstAndPagesWithoutGapsOrDuplicates() throws Exception {
        String bank = createAccount("BANK", "HDFC", 0);
        for (int day = 1; day <= 5; day++) {
            createTransaction(fields("EXPENSE", 100L * day, bank, null, groceries, "Day " + day, "2026-10-0" + day));
        }
        createTransaction(fields("EXPENSE", 999, bank, null, groceries, "Also day 3", "2026-10-03"));

        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            var request = get(path("/transactions")).with(me).param("limit", "2");
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            String page = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            seen.addAll(JsonPath.read(page, "$.items[*].occurredOn"));
            cursor = JsonPath.read(page, "$.nextCursor");
        } while (cursor != null);
        assertThat(seen).containsExactly("2026-10-05", "2026-10-04", "2026-10-03", "2026-10-03", "2026-10-02", "2026-10-01");

        mvc.perform(get(path("/transactions")).with(me).param("from", "2026-10-02").param("to", "2026-10-03"))
                .andExpect(jsonPath("$.items.length()").value(3));
        mvc.perform(get(path("/transactions")).with(me).param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(get(path("/transactions")).with(me).param("limit", "201")).andExpect(status().isBadRequest());
        mvc.perform(get(path("/transactions")).with(me).param("cursor", "garbage"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-cursor"));
    }

    @Test
    void searchFindsByWordsAmountKindAndTagAndTotalsWhatItFound() throws Exception {
        String bank = createAccount("BANK", "HDFC savings", 0);
        String wallet = createAccount("CASH", "Wallet", 0);
        createTransaction(fields("EXPENSE", 45_000, bank, null, groceries, "BigBasket order", "2026-10-02"));
        createTransaction(fields("EXPENSE", 12_050, wallet, null, groceries, "Vegetables 50%", "2026-10-03"));
        createTransaction(fields("INCOME", 9_000_000, bank, null, salary, "October salary", "2026-10-01"));
        createTransaction(fields("TRANSFER", 200_000, bank, wallet, null, "ATM", "2026-10-04"));

        // Words match the description, the category and the account, ignoring case.
        assertThat(descriptions("q", "bigbasket")).containsExactly("BigBasket order");
        assertThat(descriptions("q", "GROCERIES")).containsExactly("Vegetables 50%", "BigBasket order");
        assertThat(descriptions("q", "wallet")).containsExactly("ATM", "Vegetables 50%");
        // % and _ are literal characters, not wildcards.
        assertThat(descriptions("q", "50%")).containsExactly("Vegetables 50%");
        assertThat(descriptions("q", "_")).isEmpty();
        // A number finds that exact amount, written as people write it.
        assertThat(descriptions("q", "₹90,000")).containsExactly("October salary");
        assertThat(descriptions("q", "120.50")).containsExactly("Vegetables 50%");

        assertThat(descriptions("kind", "income")).containsExactly("October salary");
        assertThat(descriptions("minAmount", "12050", "maxAmount", "45000")).containsExactly("Vegetables 50%", "BigBasket order");
        assertThat(descriptions("tag", "test")).hasSize(4);
        assertThat(descriptions("tag", "tes")).isEmpty();
        assertThat(descriptions("kind", "EXPENSE", "accountId", bank)).containsExactly("BigBasket order");

        mvc.perform(get(path("/transactions/summary")).with(me).param("from", "2026-10-01").param("to", "2026-10-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals[0].currency").value("INR"))
                .andExpect(jsonPath("$.totals[0].count").value(4))
                .andExpect(jsonPath("$.totals[0].spent").value(57_050))
                .andExpect(jsonPath("$.totals[0].earned").value(9_000_000))
                .andExpect(jsonPath("$.totals[0].moved").value(200_000));
        mvc.perform(get(path("/transactions/summary")).with(me).param("q", "nothing like this"))
                .andExpect(jsonPath("$.totals.length()").value(0));

        // The export holds exactly what the filter found.
        String csv = mvc.perform(get(path("/exports/transactions.csv")).with(me).param("kind", "EXPENSE").param("q", "veg"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(csv.lines()).hasSize(2).last().asString().contains("Vegetables 50%");

        mvc.perform(get(path("/transactions")).with(me).param("kind", "REFUND"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-kind"));
        mvc.perform(get(path("/transactions")).with(me).param("minAmount", "500").param("maxAmount", "100"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-range"));
        mvc.perform(get(path("/transactions/summary")).with(me).param("q", "x".repeat(101)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-search"));
    }

    @Test
    void withinADayTheOneAddedLastComesFirstWhateverItsId() throws Exception {
        String bank = createAccount("BANK", "HDFC", 0);
        createTransaction(withId("ffffffff-ffff-4fff-bfff-ffffffffffff".replace('f', 'e'), fields("EXPENSE", 100, bank, null, groceries, "First", "2026-10-07")));
        createTransaction(withId("00000000-0000-4000-8000-000000000001", fields("EXPENSE", 100, bank, null, groceries, "Second", "2026-10-07")));
        createTransaction(withId("77777777-7777-4777-8777-777777777777", fields("EXPENSE", 100, bank, null, groceries, "Third", "2026-10-07")));
        assertThat(descriptions()).containsExactly("Third", "Second", "First");
        // And pages still join up without gaps.
        String page = mvc.perform(get(path("/transactions")).with(me).param("limit", "2")).andReturn().getResponse().getContentAsString();
        String next = mvc.perform(get(path("/transactions")).with(me).param("limit", "2").param("cursor", JsonPath.<String>read(page, "$.nextCursor")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(next, "$.items[*].description")).containsExactly("First");
    }

    private List<String> descriptions(String... params) throws Exception {
        var request = get(path("/transactions")).with(me);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return JsonPath.read(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.items[*].description");
    }

    @Test
    void commandsArePublishedToKafkaKeyedBySpace() throws Exception {
        String bank = createAccount("BANK", "HDFC", 7_700);

        var overrides = new Properties();
        overrides.put("auto.offset.reset", "earliest");
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("test-" + UUID.randomUUID(), null, null, overrides)) {
            List<TopicPartition> partitions = consumer.partitionsFor("ledger.commands.v1").stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            List<String> mine = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (mine.isEmpty() && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                    if (record.key().equals(space)) {
                        assertThat(new String(record.headers().lastHeader("eventType").value())).isEqualTo("ledger.post");
                        mine.add(JsonPath.read(record.value(), "$.idempotencyKey"));
                    }
                }
            }
            assertThat(mine).containsExactly("account:" + bank + ":opening:v1");
        }
        assertThat(jdbc.sql("select count(*) from outbox_events where aggregate_id = ? and published_at is null")
                .param(UUID.fromString(space)).query(Long.class).single()).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private String path(String rest) {
        return "/api/core/spaces/" + space + rest;
    }

    private String createAccount(String kind, String name, long opening) throws Exception {
        return id(mvc.perform(post(path("/accounts")).with(me).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"%s\",\"name\":\"%s\",\"openingBalance\":%d,\"openingDate\":\"2026-10-01\"}".formatted(kind, name, opening)))
                .andExpect(status().isCreated()));
    }

    private ResultActions createTransaction(String body) throws Exception {
        return mvc.perform(post(path("/transactions")).with(me).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions replace(String id, int version, String fields) throws Exception {
        return mvc.perform(put(path("/transactions/" + id)).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + "," + fields.substring(1)));
    }

    private static String fields(String kind, long amount, String account, String to, String category, String description) {
        return fields(kind, amount, account, to, category, description, "2026-10-05");
    }

    private static String fields(String kind, long amount, String account, String to, String category, String description, String date) {
        return """
                {"kind":"%s","amount":%d,"accountId":"%s","toAccountId":%s,"categoryId":%s,"occurredOn":"%s","description":"%s","tags":["test"]}
                """.formatted(kind, amount, account, quoted(to), quoted(category), date, description);
    }

    private static String withId(String id, String fields) {
        return "{\"id\":\"" + id + "\"," + fields.strip().substring(1);
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    /** This space's queued ledger commands, oldest first. */
    private List<String> commands() {
        return jdbc.sql("select payload::text from outbox_events where aggregate_id = ? order by id")
                .param(UUID.fromString(space)).query(String.class).list();
    }

    private Map<String, Object> command(int index) {
        return JsonPath.read(commands().get(index), "$");
    }

    private List<Map<String, Object>> legs(int index) {
        return JsonPath.read(commands().get(index), "$.legs");
    }

    private static Map<String, Object> leg(String ref, String type, long amount) {
        // JsonPath reads numbers that fit an int as Integer.
        Object number = amount == (int) amount ? (Object) (int) amount : amount;
        return Map.of("accountRef", ref, "accountType", type, "currency", "INR", "amount", number);
    }
}
