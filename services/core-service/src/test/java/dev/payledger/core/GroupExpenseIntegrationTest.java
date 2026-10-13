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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Shared expenses post to the group's ledger book; balances come back from a fake ledger. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GroupExpenseIntegrationTest {

    @DynamicPropertySource
    static void ledgerUrl(DynamicPropertyRegistry registry) {
        registry.add("payledger.ledger.url", FakeLedger::url);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    RequestPostProcessor me;
    String group;
    String priya;
    String rahul;
    String amit;

    @BeforeEach
    void group() throws Exception {
        FakeLedger.DOWN.set(false);
        String subject = UUID.randomUUID().toString();
        me = jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true).claim("name", "Priya"));
        mvc.perform(get("/api/core/me").with(me));
        group = JsonPath.read(mvc.perform(post("/api/core/groups").with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Goa\"}")).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post(path("/members")).with(me).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Rahul\"}"));
        String members = mvc.perform(post(path("/members")).with(me).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Amit\"}"))
                .andReturn().getResponse().getContentAsString();
        priya = member(members, "Priya");
        rahul = member(members, "Rahul");
        amit = member(members, "Amit");
    }

    @Test
    void anEqualSplitPostsEachShareLessWhatEachPaidToTheGroupsBook() throws Exception {
        // ₹1,000 dinner paid by Priya, split three ways: 333.34 / 333.33 / 333.33.
        String id = id(expense(expenseBody("Dinner", 100_000, payer(priya, 100_000), "EQUAL", share(priya), share(rahul), share(amit)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parts[0].share").value(33_334)).andExpect(jsonPath("$.parts[0].paid").value(100_000))
                .andExpect(jsonPath("$.parts[1].share").value(33_333)));

        Map<String, Object> command = command(0);
        assertThat(command).containsEntry("bookRef", "group:" + group).containsEntry("bookKind", "GROUP")
                .containsEntry("idempotencyKey", "gexp:" + id + ":v1").containsEntry("entryType", "GROUP_EXPENSE");
        assertThat(legs(0)).containsExactly(
                leg(priya, -66_666), leg(rahul, 33_333), leg(amit, 33_333));
        assertThat(jdbc.sql("select aggregate_type from outbox_events where aggregate_id = ?").param(UUID.fromString(group))
                .query(String.class).list()).containsOnly("Group");
    }

    @Test
    void everySplitTypeAddsUpAndBadSplitsAreRefused() throws Exception {
        // Two payers, exact amounts.
        expense(expenseBody("Hotel", 300_000, payers(payer(priya, 200_000), payer(rahul, 100_000)), "EXACT",
                share(priya, 50_000), share(rahul, 150_000), share(amit, 100_000)))
                .andExpect(status().isCreated());
        assertThat(legs(0)).containsExactly(leg(priya, -150_000), leg(rahul, 50_000), leg(amit, 100_000));
        expense(expenseBody("Fuel", 10_000, payer(amit, 10_000), "PERCENT", share(priya, 5_000), share(rahul, 5_000)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.parts[0].input").value(5_000));
        expense(expenseBody("Cab", 900, payer(rahul, 900), "SHARES", share(priya, 2), share(rahul, 1)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.parts[0].share").value(600));

        expense(expenseBody("Bad", 1_000, payer(priya, 1_000), "EXACT", share(priya, 500), share(rahul, 400)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-split"));
        expense(expenseBody("Bad", 1_000, payer(priya, 900), "EQUAL", share(priya), share(rahul)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-payers"));
        expense(expenseBody("Bad", 1_000, payer(priya, 1_000), "PERCENT", share(priya, 6_000), share(rahul, 3_000)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-split"));
        expense(expenseBody("Bad", 1_000, payer(priya, 1_000), "EQUAL", share(UUID.randomUUID().toString())))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-split"));
        expense(expenseBody("Bad", 0, payer(priya, 0), "EQUAL", share(priya))).andExpect(status().isBadRequest());
    }

    @Test
    void editingReversesAndRepostsAndDeletingReverses() throws Exception {
        String id = id(expense(expenseBody("Lunch", 3_000, payer(priya, 3_000), "EQUAL", share(priya), share(rahul), share(amit))));
        mvc.perform(put(path("/expenses/" + id)).with(me).param("version", "1").contentType(MediaType.APPLICATION_JSON)
                        .content(expenseBody("Lunch", 4_000, payer(rahul, 4_000), "EQUAL", share(priya), share(rahul))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.parts.length()").value(2));
        assertThat(command(1)).containsEntry("commandType", "ledger.reverse").containsEntry("reversesIdempotencyKey", "gexp:" + id + ":v1");
        assertThat(legs(2)).containsExactly(leg(priya, 2_000), leg(rahul, -2_000));
        mvc.perform(put(path("/expenses/" + id)).with(me).param("version", "1").contentType(MediaType.APPLICATION_JSON)
                        .content(expenseBody("Stale", 4_000, payer(rahul, 4_000), "EQUAL", share(priya))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("version-conflict"));

        mvc.perform(delete(path("/expenses/" + id)).with(me).param("version", "2")).andExpect(status().isNoContent());
        assertThat(command(3)).containsEntry("reversesIdempotencyKey", "gexp:" + id + ":v2");
        mvc.perform(get(path("/activity")).with(me)).andExpect(jsonPath("$.expenses.length()").value(0));
    }

    @Test
    void anExpenseThatMovesNothingPostsNothingUntilItDoes() throws Exception {
        String id = id(expense(expenseBody("My coffee", 500, payer(priya, 500), "EQUAL", share(priya))));
        assertThat(commands()).isEmpty();
        mvc.perform(put(path("/expenses/" + id)).with(me).param("version", "1").contentType(MediaType.APPLICATION_JSON)
                .content(expenseBody("Coffees", 1_000, payer(priya, 1_000), "EQUAL", share(priya), share(rahul)))).andExpect(status().isOk());
        // Nothing to reverse: just the new entry.
        assertThat(commands()).hasSize(1);
        assertThat(command(0)).containsEntry("idempotencyKey", "gexp:" + id + ":v2");
    }

    @Test
    void retryingWithTheSameIdAddsOnce() throws Exception {
        String clientId = UUID.randomUUID().toString();
        String body = "{\"id\":\"" + clientId + "\"," + expenseBody("Tea", 200, payer(priya, 200), "EQUAL", share(priya), share(rahul)).substring(1);
        expense(body).andExpect(status().isCreated());
        expense(body).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(clientId));
        assertThat(commands()).hasSize(1);
    }

    @Test
    void settlingUpMovesBothBalancesTowardsZeroAndBalancesComeFromTheLedger() throws Exception {
        expense(expenseBody("Dinner", 90_000, payer(priya, 90_000), "EQUAL", share(priya), share(rahul), share(amit)));
        String settlement = JsonPath.read(mvc.perform(post(path("/settlements")).with(me).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromMemberId\":\"%s\",\"toMemberId\":\"%s\",\"amount\":30000,\"occurredOn\":\"2026-10-06\",\"note\":\"UPI\"}"
                                .formatted(rahul, priya)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        assertThat(legs(1)).containsExactly(leg(rahul, -30_000), leg(priya, 30_000));
        mvc.perform(post(path("/settlements")).with(me).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromMemberId\":\"%s\",\"toMemberId\":\"%s\",\"amount\":1,\"occurredOn\":\"2026-10-06\"}".formatted(rahul, rahul)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-settlement"));

        FakeLedger.RESPONSES.put("/api/v2/books/group:" + group + "/balances", """
                [{"accountRef":"member:%s","accountType":"ASSET","currency":"INR","balance":-30000},
                 {"accountRef":"member:%s","accountType":"ASSET","currency":"INR","balance":30000}]
                """.formatted(priya, amit));
        mvc.perform(get(path("/balances")).with(me))
                .andExpect(jsonPath("$.known").value(true))
                .andExpect(jsonPath("$.balance['" + priya + "']").value(-30_000))
                .andExpect(jsonPath("$.balance['" + rahul + "']").value(0))
                .andExpect(jsonPath("$.payments.length()").value(1))
                .andExpect(jsonPath("$.payments[0].from").value(amit)).andExpect(jsonPath("$.payments[0].to").value(priya))
                .andExpect(jsonPath("$.payments[0].amount").value(30_000));
        FakeLedger.DOWN.set(true);
        mvc.perform(get(path("/balances")).with(me)).andExpect(jsonPath("$.known").value(false));

        mvc.perform(delete(path("/settlements/" + settlement)).with(me)).andExpect(status().isNoContent());
        assertThat(command(2)).containsEntry("reversesIdempotencyKey", "settle:" + settlement + ":v1");
    }

    @Test
    void someoneWhoOwesOrIsOwedCantLeaveUntilSettled() throws Exception {
        expense(expenseBody("Snacks", 1_000, payer(priya, 1_000), "EQUAL", share(priya), share(amit)));
        mvc.perform(delete(path("/members/" + amit)).with(me))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("member-has-balance"));
        mvc.perform(post(path("/settlements")).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromMemberId\":\"%s\",\"toMemberId\":\"%s\",\"amount\":500,\"occurredOn\":\"2026-10-06\"}".formatted(amit, priya)));
        mvc.perform(delete(path("/members/" + amit)).with(me)).andExpect(status().isNoContent());
        // Rahul was never in an expense: he can go at once.
        mvc.perform(delete(path("/members/" + rahul)).with(me)).andExpect(status().isNoContent());
    }

    @Test
    void outsidersAndArchivedGroupsAreRefused() throws Exception {
        String stranger = UUID.randomUUID().toString();
        var them = jwt().jwt(j -> j.subject(stranger).claim("email", stranger + "@example.com").claim("email_verified", true));
        mvc.perform(get(path("/activity")).with(them)).andExpect(status().isNotFound());
        mvc.perform(patch("/api/core/groups/" + group).with(me).contentType(MediaType.APPLICATION_JSON).content("{\"archived\":true}"));
        expense(expenseBody("Late", 100, payer(priya, 100), "EQUAL", share(priya), share(rahul)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("group-archived"));
    }

    // ---------------------------------------------------------------- helpers

    private String path(String rest) {
        return "/api/core/groups/" + group + rest;
    }

    private ResultActions expense(String body) throws Exception {
        return mvc.perform(post(path("/expenses")).with(me).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String expenseBody(String description, long amount, String paidBy, String split, String... shares) {
        return """
                {"description":"%s","amount":%d,"occurredOn":"2026-10-05","category":"Food","paidBy":[%s],"split":"%s","shares":[%s]}
                """.formatted(description, amount, paidBy, split, String.join(",", shares));
    }

    private static String payer(String member, long amount) {
        return "{\"memberId\":\"" + member + "\",\"value\":" + amount + "}";
    }

    private static String payers(String... payers) {
        return String.join(",", payers);
    }

    private static String share(String member) {
        return "{\"memberId\":\"" + member + "\"}";
    }

    private static String share(String member, long value) {
        return "{\"memberId\":\"" + member + "\",\"value\":" + value + "}";
    }

    private static String member(String group, String name) {
        return JsonPath.<List<String>>read(group, "$.members[?(@.name == '" + name + "')].id").getFirst();
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private List<String> commands() {
        return jdbc.sql("select payload::text from outbox_events where aggregate_id = ? order by id")
                .param(UUID.fromString(group)).query(String.class).list();
    }

    private Map<String, Object> command(int index) {
        return JsonPath.read(commands().get(index), "$");
    }

    private List<Map<String, Object>> legs(int index) {
        return JsonPath.read(commands().get(index), "$.legs");
    }

    private static Map<String, Object> leg(String member, long amount) {
        return Map.of("accountRef", "member:" + member, "accountType", "ASSET", "currency", "INR", "amount", (int) amount);
    }
}
