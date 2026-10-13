package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Emails, delivered to a real (catch-all) mail server: welcome, group invites, reminders, settings and the test email. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "payledger.admin.owners=postmaster@payledger.example")
@AutoConfigureMockMvc
class NotificationIntegrationTest {

    static final RequestPostProcessor OWNER = user("postmaster@payledger.example", UUID.randomUUID().toString());
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Value("${test.mailpit.url}") String mailpit;

    @Test
    void aNewUserGetsAWelcomeEmailWithTheSetupLink() throws Exception {
        String email = unique() + "@example.com";
        mvc.perform(get("/api/core/me").with(user(email, UUID.randomUUID().toString()))).andExpect(status().isOk());
        String message = awaitMessage(email, "Welcome to PayLedger");
        assertThat(message).contains("http://localhost:5173/welcome");
    }

    @Test
    void addingSomeoneByEmailInvitesThemWithTheJoinLink() throws Exception {
        var me = newUser();
        String group = JsonPath.read(mvc.perform(post("/api/core/groups").with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Goa trip\"}")).andReturn().getResponse().getContentAsString(), "$.id");
        String friend = unique() + "@example.com";
        mvc.perform(post("/api/core/groups/" + group + "/members").with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Rahul\",\"email\":\"" + friend + "\"}")).andExpect(status().isOk());
        String token = jdbc.sql("select token from group_links where group_id = ? and revoked_at is null").param(UUID.fromString(group))
                .query(String.class).single();
        assertThat(awaitMessage(friend, "invited you to split expenses in Goa trip")).contains("/join/" + token);

        // Someone with an account is told they were added, unless they turned group emails off.
        String member = unique() + "@example.com";
        var them = user(member, UUID.randomUUID().toString());
        mvc.perform(get("/api/core/me").with(them));
        mvc.perform(post("/api/core/groups/" + group + "/members").with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Priya\",\"email\":\"" + member + "\"}")).andExpect(status().isOk());
        awaitMessage(member, "added you to Goa trip");

        String quiet = unique() + "@example.com";
        var quietOne = user(quiet, UUID.randomUUID().toString());
        mvc.perform(patch("/api/core/me/notifications").with(quietOne).contentType(MediaType.APPLICATION_JSON)
                .content("{\"GROUP_ACTIVITY\":false}")).andExpect(jsonPath("$.GROUP_ACTIVITY").value(false));
        mvc.perform(post("/api/core/groups/" + group + "/members").with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Quiet\",\"email\":\"" + quiet + "\"}")).andExpect(status().isOk());
        assertThat(outbox(quiet, "GROUP_ACTIVITY")).isZero();
    }

    @Test
    void settingsHaveSensibleDefaultsAndATestEmailOncAMinute() throws Exception {
        String email = unique() + "@example.com";
        var me = user(email, UUID.randomUUID().toString());
        mvc.perform(get("/api/core/me/notifications").with(me))
                .andExpect(jsonPath("$.BILL_REMINDERS").value(true)).andExpect(jsonPath("$.BUDGET_ALERTS").value(true))
                .andExpect(jsonPath("$.GROUP_ACTIVITY").value(true)).andExpect(jsonPath("$.PRODUCT_NEWS").value(false));
        mvc.perform(post("/api/core/me/notifications/test").with(me)).andExpect(status().isAccepted());
        mvc.perform(post("/api/core/me/notifications/test").with(me)).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("too-soon"));
        awaitMessage(email, "Test email from PayLedger");
        // The person can see what was sent to them.
        awaitSent(email);
        mvc.perform(get("/api/core/me/notifications/sent").with(me))
                .andExpect(jsonPath("$[?(@.subject == 'Test email from PayLedger')].sent_at").isNotEmpty());
    }

    @Test
    void billsDueSoonGetOneMorningDigestAndRunningAgainSendsNothingMore() throws Exception {
        String email = unique() + "@example.com";
        var me = user(email, UUID.randomUUID().toString());
        String space = JsonPath.read(mvc.perform(get("/api/core/me").with(me)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
        String account = JsonPath.read(mvc.perform(post("/api/core/spaces/" + space + "/accounts").with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"BANK\",\"name\":\"HDFC\"}")).andReturn().getResponse().getContentAsString(), "$.id");
        String categories = mvc.perform(get("/api/core/spaces/" + space + "/categories").with(me)).andReturn().getResponse().getContentAsString();
        String rent = ((List<?>) JsonPath.read(categories, "$[?(@.name == 'Rent')].id")).getFirst().toString();
        LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1);
        mvc.perform(post("/api/core/spaces/" + space + "/commitments").with(me).contentType(MediaType.APPLICATION_JSON).content("""
                {"kind":"BILL","name":"House rent","amount":2500000,"accountId":"%s","categoryId":"%s","everyMonths":1,"firstDue":"%s"}
                """.formatted(account, rent, tomorrow))).andExpect(status().isCreated());

        mvc.perform(post("/api/core/admin/emails/run-reminders").with(OWNER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.billEmails").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        String digest = awaitMessage(email, "House rent is due soon");
        assertThat(digest).contains("₹25,000").contains("http://localhost:5173/plan");

        mvc.perform(post("/api/core/admin/emails/run-reminders").with(OWNER)).andExpect(status().isOk());
        assertThat(outbox(email, "BILL_REMINDERS")).isEqualTo(1);

        // Staff see delivery status, never the body.
        mvc.perform(get("/api/core/admin/emails").with(OWNER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.emails[0].body_html").doesNotExist());
        // Customers can't run reminders.
        mvc.perform(post("/api/core/admin/emails/run-reminders").with(me)).andExpect(status().isForbidden());
    }

    @Test
    void billRemindersCanBeTurnedOff() throws Exception {
        String email = unique() + "@example.com";
        var me = user(email, UUID.randomUUID().toString());
        String id = JsonPath.read(mvc.perform(get("/api/core/me").with(me)).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(patch("/api/core/me/notifications").with(me).contentType(MediaType.APPLICATION_JSON).content("{\"BILL_REMINDERS\":false}"))
                .andExpect(jsonPath("$.BILL_REMINDERS").value(false)).andExpect(jsonPath("$.BUDGET_ALERTS").value(true));
        // Queuing directly: the switch is honoured at the source.
        jdbc.sql("update users set email_prefs = '{\"BILL_REMINDERS\":false}' where id = ?").param(UUID.fromString(id)).update();
        mvc.perform(get("/api/core/me/notifications").with(me)).andExpect(jsonPath("$.BILL_REMINDERS").value(false));
    }

    // ---------------------------------------------------------------- helpers

    private long outbox(String email, String kind) {
        return jdbc.sql("select count(*) from email_outbox where to_email = ? and kind = ?").param(email).param(kind).query(Long.class).single();
    }

    private void awaitSent(String email) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            if (jdbc.sql("select count(*) from email_outbox where to_email = ? and sent_at is not null").param(email).query(Long.class).single() > 0) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("nothing marked sent to " + email);
    }

    /** Waits for an email to arrive in Mailpit and returns its text. */
    private String awaitMessage(String to, String subjectPart) throws Exception {
        String query = URLEncoder.encode("to:" + to, StandardCharsets.UTF_8);
        for (int i = 0; i < 60; i++) {
            String found = HTTP.send(HttpRequest.newBuilder(URI.create(mailpit + "/api/v1/search?query=" + query)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            List<String> subjects = JsonPath.read(found, "$.messages[*].Subject");
            List<String> ids = JsonPath.read(found, "$.messages[*].ID");
            for (int k = 0; k < subjects.size(); k++) {
                if (subjects.get(k).contains(subjectPart)) {
                    return HTTP.send(HttpRequest.newBuilder(URI.create(mailpit + "/api/v1/message/" + ids.get(k))).timeout(Duration.ofSeconds(5)).build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
                }
            }
            Thread.sleep(250);
        }
        throw new AssertionError("no email to " + to + " about \"" + subjectPart + "\"");
    }

    private static String unique() {
        return "n" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static RequestPostProcessor newUser() {
        return user(unique() + "@example.com", UUID.randomUUID().toString());
    }

    private static RequestPostProcessor user(String email, String subject) {
        return jwt().jwt(j -> j.subject(subject).claim("email", email).claim("email_verified", true));
    }
}
