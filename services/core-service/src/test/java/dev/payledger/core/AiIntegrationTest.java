package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
import dev.payledger.core.ai.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI reading end to end, against the real database, with a scripted model in place of a provider: quick add (sure:
 * added; unsure: a draft), the Free allowance, a bank alert the rules can't fully read (read by AI, added when sure,
 * undone), masking, and the audit trail staff see.
 */
@Import({TestcontainersConfiguration.class, AiIntegrationTest.ScriptedModel.class})
@SpringBootTest(properties = {"payledger.capture.inbound-secret=test-inbound-secret", "payledger.capture.domain=in.example.com",
        "payledger.admin.owners=ai-boss@payledger.example", "payledger.ai.price.input-per-million=1.0", "payledger.ai.price.output-per-million=5.0"})
@AutoConfigureMockMvc
class AiIntegrationTest {

    /** What the model is asked (the masked text) and what it answers, per test. */
    static final List<String> ASKED = new CopyOnWriteArrayList<>();
    static volatile Function<String, String> answer = q -> "{}";

    @TestConfiguration
    static class ScriptedModel {
        @Bean
        @Primary
        LlmClient scriptedLlm() {
            return new LlmClient() {
                @Override
                public Completion complete(String system, String user, int maxTokens) {
                    ASKED.add(user);
                    return new Completion(answer.apply(user), 600, 120, "scripted-1");
                }

                @Override
                public String provider() {
                    return "scripted";
                }

                @Override
                public String model() {
                    return "scripted-1";
                }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    @BeforeEach
    void reset() {
        ASKED.clear();
    }

    @Test
    void quickAddAddsWhatItsSureOfAndDraftsTheRest() throws Exception {
        var me = user();
        String space = JsonPath.read(body(get("/api/core/me"), me, null), "$.spaces[0].id");
        String hdfc = JsonPath.read(body(post("/api/core/spaces/" + space + "/accounts"), me, "{\"kind\":\"BANK\",\"name\":\"HDFC Savings\",\"openingBalance\":0}"), "$.id");
        mvc.perform(get("/api/core/ai/status").with(me)).andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.usage.limit").value(25));

        answer = q -> """
                {"cleanText":"450 petrol yesterday on HDFC Savings, and 200 auto","isTransaction":true,"reason":"",
                 "entries":[{"kind":"EXPENSE","amount":"450","currency":"INR","date":"%s","dateGiven":true,"description":"Petrol",
                             "merchant":null,"account":"HDFC Savings","toAccount":null,"category":"Fuel & transport","counterparty":null,
                             "confidence":0.97,"missing":[]},
                            {"kind":"EXPENSE","amount":"200","currency":"INR","date":"%s","dateGiven":false,"description":"Auto",
                             "merchant":null,"account":null,"toAccount":null,"category":"Fuel & transport","counterparty":null,
                             "confidence":0.9,"missing":["account","date"]}]}
                """.formatted(today.minusDays(1), today);
        String r = body(post("/api/core/ai/quick-add"), me, "{\"text\":\"four fifty petrol yesterday on HDFC savings 450 and 200 auto\",\"channel\":\"VOICE\"}");
        assertThat(JsonPath.<Boolean>read(r, "$.ai")).isTrue();
        assertThat(JsonPath.<String>read(r, "$.entries[0].status")).isEqualTo("ADDED");
        assertThat(JsonPath.<String>read(r, "$.entries[0].accountId")).isEqualTo(hdfc);
        assertThat(JsonPath.<String>read(r, "$.entries[1].status")).isEqualTo("DRAFT");
        assertThat(JsonPath.<List<String>>read(r, "$.entries[1].why")).contains("Which account? You didn't say.");
        assertThat(JsonPath.<Integer>read(r, "$.usage.used")).isEqualTo(1);
        String added = JsonPath.read(r, "$.entries[0].transactionId");
        assertThat(jdbc.sql("select count(*) from transactions where id = ?::uuid and amount = 45000 and 'ai' = any(tags) and occurred_on = ?")
                .params(added, today.minusDays(1)).query(Long.class).single()).isEqualTo(1);

        // Logged: tokens, cost (600 in at $1/M + 120 out at $5/M = 1,200 millionths of a dollar), outcome, the transaction.
        var row = jdbc.sql("""
                        select feature, channel, input_tokens, output_tokens, cost_micros, counted, outcome, array_to_string(transaction_ids, ',') as t
                        from ai_requests where user_id = (select id from users where email = ?) order by at desc limit 1
                        """).param(email(me)).query().singleRow();
        assertThat(row.get("feature")).isEqualTo("QUICK_ADD");
        assertThat(row.get("channel")).isEqualTo("VOICE");
        assertThat(((Number) row.get("cost_micros")).longValue()).isEqualTo(1_200L);
        assertThat(row.get("counted")).isEqualTo(true);
        assertThat(row.get("outcome")).isEqualTo("PARTLY_ADDED");
        assertThat((String) row.get("t")).isEqualTo(added);
        // Account names go to the model; nothing else about the money does.
        assertThat(ASKED.getFirst()).contains("ACCOUNTS: HDFC Savings").doesNotContain("openingBalance");
    }

    @Test
    void overTheFreeAllowanceItsASimpleReadingAndNoModelCall() throws Exception {
        var me = user();
        String space = JsonPath.read(body(get("/api/core/me"), me, null), "$.spaces[0].id");
        body(post("/api/core/spaces/" + space + "/accounts"), me, "{\"kind\":\"CASH\",\"name\":\"Cash\",\"openingBalance\":0}");
        jdbc.sql("""
                        insert into ai_requests (id, user_id, feature, provider, model, status, counted)
                        select gen_random_uuid(), (select id from users where email = ?), 'QUICK_ADD', 'scripted', 'scripted-1', 'OK', true
                        from generate_series(1, 25)
                        """).param(email(me)).update();
        String r = body(post("/api/core/ai/quick-add"), me, "{\"text\":\"250 swiggy\"}");
        assertThat(JsonPath.<Boolean>read(r, "$.ai")).isFalse();
        assertThat(JsonPath.<String>read(r, "$.notice")).contains("25 AI reads");
        assertThat(JsonPath.<Integer>read(r, "$.entries[0].amount")).isEqualTo(25_000);
        assertThat(JsonPath.<String>read(r, "$.entries[0].status")).isEqualTo("DRAFT");
        assertThat(JsonPath.<String>read(r, "$.entries[0].description")).isEqualTo("Swiggy");
        assertThat(ASKED).isEmpty();
    }

    @Test
    void anAlertTheRulesCantFullyReadIsReadByAiAddedWhenSureAndCanBeUndone() throws Exception {
        var me = user();
        String space = JsonPath.read(body(get("/api/core/me"), me, null), "$.spaces[0].id");
        String axis = JsonPath.read(body(post("/api/core/spaces/" + space + "/accounts"), me, "{\"kind\":\"BANK\",\"name\":\"Axis 1180\",\"openingBalance\":0}"), "$.id");
        String address = JsonPath.read(body(get("/api/core/capture"), me, null), "$.address");
        assertThat(JsonPath.<Boolean>read(body(get("/api/core/capture"), me, null), "$.autoAdd")).isTrue();

        // Axis's layout: no "to"/"at" payee the rules recognise and no date they read, so the AI is asked.
        answer = q -> """
                {"cleanText":"","isTransaction":true,"entries":[{"kind":"EXPENSE","amount":"2499.00","currency":"INR","date":"%s",
                  "dateGiven":true,"description":"Croma","merchant":"Croma","account":null,"category":"Shopping","confidence":0.97,"missing":["account"]}]}
                """.formatted(today);
        String email = """
                From: alerts@axis.bank.in
                Subject: INR 2499.00 was debited from your A/c no. XX1180.
                Message-ID: <axis-%s@axis.bank.in>
                Content-Type: text/plain

                Dear Customer,
                Amount Debited:
                INR 2499.00
                Account Number:
                XX1180
                Transaction Info:
                POS/CROMA RETAIL
                Please do not share your user ID/password or CVV/OTP with anyone. Call 18001035577.
                """.formatted(UUID.randomUUID()).replace("\n", "\r\n");
        mvc.perform(post("/inbound/email").header("X-Inbound-Secret", "test-inbound-secret").header("X-Envelope-To", address).content(email))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("added-automatically"));
        assertThat(ASKED).hasSize(1);
        // Masked: the account digits and phone number never reach the model; the amount does.
        assertThat(ASKED.getFirst()).contains("2499.00", "[ACCOUNT]").doesNotContain("1180", "18001035577");

        String view = body(get("/api/core/capture"), me, null);
        String item = JsonPath.<List<String>>read(view, "$.items[?(@.merchant == 'Croma')].id").getFirst();
        assertThat(JsonPath.<List<String>>read(view, "$.items[?(@.merchant == 'Croma')].status")).containsExactly("ADDED");
        assertThat(JsonPath.<List<Boolean>>read(view, "$.items[?(@.merchant == 'Croma')].autoAdded")).containsExactly(true);
        assertThat(JsonPath.<List<String>>read(view, "$.items[?(@.merchant == 'Croma')].readBy")).containsExactly("AI");
        assertThat(jdbc.sql("select count(*) from transactions where space_id = ?::uuid and account_id = ?::uuid and amount = 249900 and deleted_at is null")
                .params(space, axis).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select outcome from ai_requests where captured_item_id = ?::uuid").param(item).query(String.class).single()).isEqualTo("AUTO_ADDED");

        // Wrong? Undo: the transaction goes, the entry waits again with its text.
        mvc.perform(post("/api/core/capture/items/" + item + "/undo").with(me)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.body").isNotEmpty());
        assertThat(jdbc.sql("select count(*) from transactions where space_id = ?::uuid and amount = 249900 and deleted_at is null")
                .param(space).query(Long.class).single()).isZero();

        // The AI says an email isn't a transaction, and the rules found nothing either: skipped.
        answer = q -> "{\"isTransaction\":false,\"reason\":\"a promotional offer\",\"entries\":[]}";
        mvc.perform(post("/inbound/email").header("X-Inbound-Secret", "test-inbound-secret").header("X-Envelope-To", address).content("""
                        From: alerts@axis.bank.in
                        Subject: Your Axis account update
                        Message-ID: <axis-%s@axis.bank.in>

                        Dear Customer, your monthly account summary of INR 2,000 average balance is ready.
                        """.formatted(UUID.randomUUID()).replace("\n", "\r\n")))
                .andExpect(jsonPath("$.result").value("not-a-transaction"));

        // Staff see it all: totals, per user, and each request.
        var boss = jwt().jwt(j -> j.subject("ai-boss").claim("email", "ai-boss@payledger.example").claim("email_verified", true));
        String summary = body(get("/api/core/admin/ai"), boss, null);
        assertThat(JsonPath.<Integer>read(summary, "$.totals.requests")).isGreaterThanOrEqualTo(2);
        assertThat(JsonPath.<List<String>>read(summary, "$.users[*].email")).contains(email(me));
        String requests = body(get("/api/core/admin/ai/requests?feature=CAPTURE"), boss, null);
        assertThat(JsonPath.<List<String>>read(requests, "$[*].outcome")).contains("AUTO_ADDED", "NOT_A_TRANSACTION");
        // Not for people without the permission.
        mvc.perform(get("/api/core/admin/ai").with(me)).andExpect(status().isForbidden());
    }

    @Test
    void voiceModeTurnsOneSentenceIntoSeveralCheckedProposalsAndAnswersFromTheData() throws Exception {
        var me = user();
        String space = JsonPath.read(body(get("/api/core/me"), me, null), "$.spaces[0].id");
        String hdfc = JsonPath.read(body(post("/api/core/spaces/" + space + "/accounts"), me, "{\"kind\":\"BANK\",\"name\":\"HDFC Savings\",\"openingBalance\":0}"), "$.id");
        String categories = body(get("/api/core/spaces/" + space + "/categories"), me, null);
        String eatingOut = JsonPath.<List<String>>read(categories, "$[?(@.name == 'Eating out')].id").getFirst();
        String groceries = JsonPath.<List<String>>read(categories, "$[?(@.name == 'Groceries')].id").getFirst();
        // Something already spent on eating out this month, for the question.
        body(post("/api/core/spaces/" + space + "/transactions"), me, """
                {"id":"%s","kind":"EXPENSE","amount":120000,"accountId":"%s","categoryId":"%s","occurredOn":"%s","description":"Dinner"}
                """.formatted(UUID.randomUUID(), hdfc, eatingOut, today));

        answer = q -> """
                {"heard":"set groceries budget 6000, 450 petrol yesterday from HDFC Savings, how much on eating out","say":"All done!","intent":null,
                 "navigate":null,"actions":[
                  {"type":"set_budget","category":"Groceries","amount":"6000","month":"%s","confidence":0.97},
                  {"type":"add_transaction","kind":"EXPENSE","amount":"450","currency":"INR","date":"%s","dateGiven":true,"description":"Petrol",
                   "account":"HDFC Savings","category":"Fuel & transport","confidence":0.97,"missing":[]},
                  {"type":"add_transaction","kind":"EXPENSE","amount":"300","currency":"INR","date":"%s","dateGiven":false,"description":"Auto",
                   "account":null,"category":"Fuel & transport","confidence":0.8,"missing":["account"]},
                  {"type":"ask_spending","metric":"spent","category":"Eating out","from":"%s","to":"%s"}]}
                """.formatted(java.time.YearMonth.from(today), today.minusDays(1), today, today.withDayOfMonth(1), today);
        String r = body(post("/api/core/ai/assistant"), me, """
                {"turns":[{"role":"person","text":"set groceries budget six thousand, 450 petrol yesterday from HDFC savings and 300 auto, how much did I spend on eating out"}],
                 "shown":[],"page":"/dashboard","channel":"VOICE"}
                """);
        assertThat(JsonPath.<List<String>>read(r, "$.proposals[*].type")).containsExactly("BUDGET", "TRANSACTION", "TRANSACTION");
        assertThat(JsonPath.<String>read(r, "$.proposals[0].body.categoryId")).isEqualTo(groceries);
        assertThat(JsonPath.<Integer>read(r, "$.proposals[0].body.amount")).isEqualTo(600_000);
        assertThat(JsonPath.<String>read(r, "$.proposals[1].body.accountId")).isEqualTo(hdfc);
        assertThat(JsonPath.<Boolean>read(r, "$.proposals[1].ready")).isTrue();
        assertThat(JsonPath.<Boolean>read(r, "$.proposals[2].ready")).isFalse();
        assertThat(JsonPath.<List<String>>read(r, "$.proposals[2].why")).contains("Which account?");
        // Said by us, not the model's "All done!": nothing is saved yet. The answer comes from the data.
        String say = JsonPath.read(r, "$.say");
        assertThat(say).startsWith("A budget and 2 entries ready.").doesNotContain("All done").contains("1,200").contains("Eating out");
        assertThat(jdbc.sql("select count(*) from transactions where space_id = ?::uuid and description = 'Petrol'").param(space).query(Long.class).single()).isZero();
        assertThat(JsonPath.<Integer>read(r, "$.usage.used")).isEqualTo(1);

        // A plain yes: the model's intent comes back for the app to act on.
        answer = q -> "{\"heard\":\"yes add them\",\"say\":\"Okay.\",\"intent\":\"CONFIRM\",\"actions\":[]}";
        String yes = body(post("/api/core/ai/assistant"), me, """
                {"turns":[{"role":"person","text":"set groceries budget"},{"role":"assistant","text":"A budget ready."},{"role":"person","text":"yes add them"}],
                 "shown":[{"index":0,"type":"BUDGET","summary":"Groceries 6000"}],"page":"/dashboard"}
                """);
        assertThat(JsonPath.<String>read(yes, "$.intent")).isEqualTo("CONFIRM");
        assertThat(ASKED.getLast()).contains("[0] BUDGET: Groceries 6000");
    }

    @Test
    void whenTheRulesReadEverythingNoAiIsSpent() throws Exception {
        var me = user();
        String space = JsonPath.read(body(get("/api/core/me"), me, null), "$.spaces[0].id");
        body(post("/api/core/spaces/" + space + "/accounts"), me, "{\"kind\":\"BANK\",\"name\":\"HDFC 1234\",\"openingBalance\":0}");
        String r = body(post("/api/core/capture/paste"), me, "{\"text\":\"Rs.450.00 debited from a/c **1234 on 14-10-26 to VPA swiggy@icici (UPI Ref No 428910283746) - HDFC Bank\"}");
        assertThat(JsonPath.<String>read(r, "$.items[0].status")).isEqualTo("ADDED");
        assertThat(JsonPath.<Boolean>read(r, "$.items[0].autoAdded")).isTrue();
        assertThat(JsonPath.<String>read(r, "$.items[0].readBy")).isEqualTo("RULES");
        assertThat(ASKED).isEmpty();
    }

    private String body(MockHttpServletRequestBuilder req, RequestPostProcessor who, String json) throws Exception {
        if (json != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(req.with(who)).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private final java.util.Map<RequestPostProcessor, String> emails = new java.util.HashMap<>();

    private RequestPostProcessor user() {
        String subject = UUID.randomUUID().toString();
        RequestPostProcessor p = jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true)
                .claim("name", "Asha Kulkarni"));
        emails.put(p, subject + "@example.com");
        return p;
    }

    private String email(RequestPostProcessor p) {
        return emails.get(p);
    }
}
