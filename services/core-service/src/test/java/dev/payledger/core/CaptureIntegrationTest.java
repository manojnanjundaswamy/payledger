package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Auto-capture: pasted SMS and forwarded emails wait in "To review", with guesses, and become transactions only when added. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"payledger.capture.inbound-secret=test-inbound-secret", "payledger.capture.domain=in.example.com"})
@AutoConfigureMockMvc
class CaptureIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void pastedAndForwardedAlertsWaitForReviewAndBecomeTransactionsWhenAdded() throws Exception {
        var me = askFirst(user());
        String space = JsonPath.read(body(get("/api/core/me"), me, null), "$.spaces[0].id");
        String hdfc = JsonPath.read(body(post("/api/core/spaces/" + space + "/accounts"), me, "{\"kind\":\"BANK\",\"name\":\"HDFC Salary\",\"openingBalance\":0}"), "$.id");

        String view = body(get("/api/core/capture"), me, null);
        String address = JsonPath.read(view, "$.address");
        assertThat(address).endsWith("@in.example.com");
        assertThat(JsonPath.<Boolean>read(view, "$.emailReady")).isTrue();

        // Two SMS and an OTP pasted together: two entries, the OTP is ignored.
        String paste = """
                Rs.450.00 debited from a/c **1234 on 14-10-26 to VPA swiggy@icici (UPI Ref No 428910283746) - HDFC Bank

                Your A/C XXXXX12345 Credited INR 5,000.00 on 13/10/26 by NEHA SHARMA. Avl Bal INR 45,210.00 - HDFC Bank

                123456 is your OTP for Rs 999 at Amazon. Do not share it.
                """;
        String pasted = body(post("/api/core/capture/paste"), me, "{\"text\":" + json(paste) + "}");
        assertThat(JsonPath.<Integer>read(pasted, "$.added")).isEqualTo(2);
        assertThat(JsonPath.<String>read(pasted, "$.items[0].accountId")).isEqualTo(hdfc);
        // The same again: nothing new.
        assertThat(JsonPath.<Integer>read(body(post("/api/core/capture/paste"), me, "{\"text\":" + json(paste) + "}"), "$.duplicates")).isEqualTo(2);

        String swiggy = JsonPath.<List<String>>read(pasted, "$.items[?(@.merchant == 'Swiggy')].id").getFirst();
        String neha = JsonPath.<List<String>>read(pasted, "$.items[?(@.merchant == 'Neha Sharma')].id").getFirst();
        String other = JsonPath.<List<String>>read(body(get("/api/core/spaces/" + space + "/categories"), me, null), "$[?(@.name == 'Eating out')].id").getFirst();
        body(post("/api/core/capture/items/" + swiggy + "/add"), me, "{\"accountId\":\"" + hdfc + "\",\"categoryId\":\"" + other + "\"}");
        body(post("/api/core/capture/items/" + neha + "/dismiss"), me, null);
        assertThat(jdbc.sql("select count(*) from transactions where space_id = ?::uuid and description = 'Swiggy' and amount = 45000 and 'captured' = any(tags)")
                .param(space).query(Long.class).single()).isEqualTo(1);
        // Added or dismissed, the original text is let go.
        assertThat(jdbc.sql("select count(*) from captured_items where id in (?::uuid, ?::uuid) and body is null").params(swiggy, neha).query(Long.class).single()).isEqualTo(2);
        mvc.perform(post("/api/core/capture/items/" + swiggy + "/add").with(me).contentType(MediaType.APPLICATION_JSON).content("{\"accountId\":\"" + hdfc + "\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("already-decided"));

        // Someone else can't touch it.
        mvc.perform(post("/api/core/capture/items/" + swiggy + "/dismiss").with(user())).andExpect(status().isNotFound());

        // A forwarded email, through the Email Worker's door.
        String email = """
                From: HDFC Bank InstaAlerts <alerts@hdfcbank.net>
                To: someone@gmail.com
                Subject: You have done a UPI txn. Check details!
                Message-ID: <abc123@hdfcbank.net>
                Content-Type: text/html; charset=UTF-8

                <html><body><p>Dear Customer, Rs.799.00 has been debited from account **1234 to VPA netflix@hdfcbank on 15-10-26.</p></body></html>
                """.replace("\n", "\r\n");
        mvc.perform(post("/inbound/email").header("X-Inbound-Secret", "wrong").header("X-Envelope-To", address).content(email))
                .andExpect(status().isNotFound());
        mvc.perform(post("/inbound/email").header("X-Inbound-Secret", "test-inbound-secret").header("X-Envelope-To", address).content(email))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("added"));
        mvc.perform(post("/inbound/email").header("X-Inbound-Secret", "test-inbound-secret").header("X-Envelope-To", address).content(email))
                .andExpect(jsonPath("$.result").value("duplicate"));
        mvc.perform(post("/inbound/email").header("X-Inbound-Secret", "test-inbound-secret").header("X-Envelope-To", "nobody@in.example.com").content(email))
                .andExpect(jsonPath("$.result").value("unknown-address"));

        // Gmail's forwarding confirmation: its code is shown in the app.
        String gmail = """
                From: Gmail Team <forwarding-noreply@google.com>
                Subject: (#123456789) Gmail Forwarding Confirmation - Receive Mail from someone@gmail.com
                Content-Type: text/plain

                someone@gmail.com has requested to automatically forward mail to your email address.
                Confirmation code: 123456789
                To allow someone@gmail.com to automatically forward mail to your address, please click the link below to confirm the request:
                https://mail-settings.google.com/mail/vf-%5BANGjdJ%5D-abc
                """.replace("\n", "\r\n");
        mvc.perform(post("/inbound/email").header("X-Inbound-Secret", "test-inbound-secret").header("X-Envelope-To", address).content(gmail))
                .andExpect(jsonPath("$.result").value("gmail-confirmation"));
        mvc.perform(get("/api/core/capture").with(me))
                .andExpect(jsonPath("$.confirmation.code").value("123456789"))
                .andExpect(jsonPath("$.confirmation.link").value("https://mail-settings.google.com/mail/vf-%5BANGjdJ%5D-abc"))
                .andExpect(jsonPath("$.pending").value(1))
                .andExpect(jsonPath("$.items[0].merchant").value("Netflix"))
                // Gmail never says it was confirmed: not on yet (the Netflix alert came before Gmail's email).
                .andExpect(jsonPath("$.forwardingOn").value(false));
        // The person says they confirmed it.
        mvc.perform(post("/api/core/capture/address/confirmed").with(me)).andExpect(jsonPath("$.forwardingOn").value(true));

        // A new address: the old one stops working.
        body(post("/api/core/capture/address/reset"), me, null);
        mvc.perform(post("/inbound/email").header("X-Inbound-Secret", "test-inbound-secret").header("X-Envelope-To", address).content(email))
                .andExpect(jsonPath("$.result").value("unknown-address"));
        // The new address starts again: not on until an email comes through it.
        mvc.perform(get("/api/core/capture").with(me)).andExpect(jsonPath("$.forwardingOn").value(false));
    }

    @Test
    void aBankStatementGoesIntoToReviewOnceAndAddAllAddsWhatsReady() throws Exception {
        var me = askFirst(user());
        String space = JsonPath.read(body(get("/api/core/me"), me, null), "$.spaces[0].id");
        String hdfc = JsonPath.read(body(post("/api/core/spaces/" + space + "/accounts"), me, "{\"kind\":\"BANK\",\"name\":\"HDFC\",\"openingBalance\":500000,\"openingDate\":\"2026-01-01\"}"), "$.id");
        String csv = """
                Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
                01/10/26,NEFT CR-ACME SOFTWARE PVT LTD-SALARY OCT,N274264,01/10/26,,"1,20,000.00","1,25,000.00"
                02/10/26,UPI-SWIGGY-swiggy@icici-ICIC0DC0099-428910283746-PAYMENT,0000428910283746,02/10/26,450.00,,"1,24,550.00"
                03/10/26,ATW-512345XXXXXX1234-S1ANBG12-BANGALORE,0000001234,03/10/26,"3,000.00",,"1,21,550.00"
                """;
        var file = new org.springframework.mock.web.MockMultipartFile("file", "Acct_Statement_HDFC.csv", "text/csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/core/capture/statement")
                        .file(file).param("accountId", hdfc).with(me))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows").value(3)).andExpect(jsonPath("$.added").value(3))
                .andExpect(jsonPath("$.adds").value(true)).andExpect(jsonPath("$.bank").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(result).contains("\"from\":\"2026-10-01\"");
        // The same file again: nothing new.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/core/capture/statement")
                        .file(file).param("accountId", hdfc).with(me))
                .andExpect(jsonPath("$.added").value(0)).andExpect(jsonPath("$.duplicates").value(3));
        // Someone else's account: no.
        var other = user();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/core/capture/statement")
                        .file(file).param("accountId", hdfc).with(other))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-account"));

        String view = body(get("/api/core/capture"), me, null);
        List<String> ids = JsonPath.read(view, "$.items[?(@.source == 'STATEMENT')].id");
        assertThat(JsonPath.<List<String>>read(view, "$.items[?(@.source == 'STATEMENT')].accountId")).containsOnly(hdfc);
        assertThat(JsonPath.<List<String>>read(view, "$.items[?(@.source == 'STATEMENT')].merchant")).contains("Swiggy", "Acme Software");
        String added = body(post("/api/core/capture/items/add"), me, "{\"ids\":[\"" + String.join("\",\"", ids) + "\"]}");
        assertThat(JsonPath.<Integer>read(added, "$.added")).isEqualTo(3);
        assertThat(jdbc.sql("select count(*) from transactions where space_id = ?::uuid and account_id = ?::uuid and 'captured' = any(tags)")
                .params(UUID.fromString(space), UUID.fromString(hdfc)).query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void whatYouFixOnceIsReadByItselfNextTime() throws Exception {
        var me = askFirst(user());
        String space = JsonPath.read(body(get("/api/core/me"), me, null), "$.spaces[0].id");
        String bank = JsonPath.read(body(post("/api/core/spaces/" + space + "/accounts"), me, "{\"kind\":\"BANK\",\"name\":\"Kotak\",\"openingBalance\":0}"), "$.id");

        // A shape the rules can't read: no "debited", no "spent".
        String first = body(post("/api/core/capture/paste"), me, "{\"text\":\"ZEPPO order 4321: amount INR 750.00, card x9876, 14-10-26\"}");
        assertThat(JsonPath.<Integer>read(first, "$.unreadable")).isEqualTo(1);
        String id = JsonPath.<List<String>>read(first, "$.items[*].id").getFirst();
        String added = body(post("/api/core/capture/items/" + id + "/add"), me,
                "{\"kind\":\"EXPENSE\",\"amount\":75000,\"accountId\":\"" + bank + "\",\"occurredOn\":\"2026-10-14\",\"description\":\"ZEPPO\"}");
        assertThat(JsonPath.<String>read(added, "$.learned")).isEqualTo("pattern");

        // The next one like it reads itself: its own amount and name.
        String next = body(post("/api/core/capture/paste"), me, "{\"text\":\"BLINKIT order 5555: amount INR 320.00, card x9876, 15-10-26\"}");
        assertThat(JsonPath.<Integer>read(next, "$.added")).isEqualTo(1);
        assertThat(JsonPath.<String>read(next, "$.items[0].readBy")).isEqualTo("LEARNED");
        assertThat(JsonPath.<Integer>read(next, "$.items[0].amount")).isEqualTo(32000);
        assertThat(JsonPath.<String>read(next, "$.items[0].merchant")).isEqualTo("Blinkit");

        // A corrected name is used next time.
        String swiggy = body(post("/api/core/capture/paste"), me, "{\"text\":\"Rs.450.00 debited from a/c **1234 on 14-10-26 to VPA swiggyltd@icici - HDFC Bank\"}");
        String sid = JsonPath.<List<String>>read(swiggy, "$.items[*].id").getFirst();
        assertThat(JsonPath.<String>read(swiggy, "$.items[0].merchant")).isEqualTo("Swiggyltd");
        assertThat(JsonPath.<String>read(body(post("/api/core/capture/items/" + sid + "/add"), me,
                "{\"accountId\":\"" + bank + "\",\"description\":\"Swiggy\"}"), "$.learned")).isEqualTo("name");
        String again = body(post("/api/core/capture/paste"), me, "{\"text\":\"Rs.280.00 debited from a/c **1234 on 15-10-26 to VPA swiggyltd@icici - HDFC Bank\"}");
        assertThat(JsonPath.<String>read(again, "$.items[0].merchant")).isEqualTo("Swiggy");
    }

    private static String json(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private String body(MockHttpServletRequestBuilder request, RequestPostProcessor who, String json) throws Exception {
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request.with(who)).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void everyEmailIsLoggedForwardsByHandAreKeptAndEachEntryRingsTheBell() throws Exception {
        String subject = UUID.randomUUID().toString();
        String myEmail = subject + "@example.com";
        RequestPostProcessor me = jwt().jwt(j -> j.subject(subject).claim("email", myEmail).claim("email_verified", true));
        String address = JsonPath.read(body(get("/api/core/capture"), me, null), "$.address");
        UUID user = jdbc.sql("select id from users where subject = :s").param("s", subject).query(UUID.class).single();

        // A bank's offer: skipped, but the page says it came and why it was left out.
        mvc.perform(post("/inbound/email").header("X-Inbound-Secret", "test-inbound-secret").header("X-Envelope-To", address).content("""
                From: offers@hdfcbank.net
                Subject: Pre-approved personal loan offer just for you

                Get a pre-approved loan at 10.5%. Apply now. T&C apply.
                """)).andExpect(jsonPath("$.result").value("not-a-transaction"));
        // Forwarded by hand from their own Gmail, not a bank alert: kept for them to look at, never filtered out.
        mvc.perform(post("/inbound/email").header("X-Inbound-Secret", "test-inbound-secret").header("X-Envelope-To", address).content("""
                From: %s
                Subject: Fwd: Plumber invoice

                Paid the plumber, please note it. Pre-approved offer inside too.
                """.formatted(myEmail))).andExpect(jsonPath("$.result").value("unreadable-kept"));
        // Two alerts in the same minute: two bells, not one an hour.
        for (String amount : new String[]{"120.00", "340.00"}) {
            mvc.perform(post("/inbound/email").header("X-Inbound-Secret", "test-inbound-secret").header("X-Envelope-To", address).content("""
                    From: alerts@hdfcbank.net
                    Subject: You have done a UPI txn

                    Rs.%s has been debited from account **1234 to VPA shop%s@okicici on 15-10-26. UPI ref 5290%s.
                    """.formatted(amount, amount.charAt(0), amount.replace(".", "")))).andExpect(jsonPath("$.result").value("added"));
        }
        assertThat(jdbc.sql("select count(*) from inbox where user_id = :u and kind = 'CAPTURED'").param("u", user).query(Long.class).single()).isEqualTo(3);

        String view = body(get("/api/core/capture"), me, null);
        assertThat(JsonPath.<List<String>>read(view, "$.recentEmails[*].result")).containsExactly("added", "added", "unreadable-kept", "not-a-transaction");
        assertThat(JsonPath.<String>read(view, "$.recentEmails[3].subject")).isEqualTo("Pre-approved personal loan offer just for you");
        assertThat(JsonPath.<Integer>read(view, "$.emailsThisWeek")).isEqualTo(4);
        assertThat(JsonPath.<Boolean>read(view, "$.forwardingOn")).isTrue();
    }

    /** These tests are about "To review": alerts we're sure of would otherwise be added on their own (AiIntegrationTest covers that). */
    private RequestPostProcessor askFirst(RequestPostProcessor me) throws Exception {
        mvc.perform(post("/api/core/capture/auto-add").with(me).contentType(MediaType.APPLICATION_JSON).content("{\"on\":false}"))
                .andExpect(status().isOk());
        return me;
    }

    private static RequestPostProcessor user() {
        String subject = UUID.randomUUID().toString();
        return jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
    }
}
