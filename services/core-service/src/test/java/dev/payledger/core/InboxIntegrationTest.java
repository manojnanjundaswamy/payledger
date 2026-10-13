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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The bell: what involves you reaches you (and your email), never the person who did it; sign-ins and changes are on record. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "payledger.admin.owners=auditor@payledger.example")
@AutoConfigureMockMvc
class InboxIntegrationTest {

    static final RequestPostProcessor OWNER = user("auditor@payledger.example", UUID.randomUUID().toString(), "owner-session");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    RequestPostProcessor asha;
    RequestPostProcessor ravi;
    String raviEmail;
    String group;
    String ashaMember;
    String raviMember;

    @BeforeEach
    void aGroupOfTwo() throws Exception {
        FakeLedger.DOWN.set(false);
        asha = user(unique() + "@example.com", UUID.randomUUID().toString(), "s-" + UUID.randomUUID());
        raviEmail = unique() + "@example.com";
        ravi = user(raviEmail, UUID.randomUUID().toString(), "s-" + UUID.randomUUID());
        mvc.perform(get("/api/core/me").with(ravi));
        String created = mvc.perform(post("/api/core/groups").with(asha).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Goa trip\"}")).andReturn().getResponse().getContentAsString();
        group = JsonPath.read(created, "$.id");
        String members = mvc.perform(post("/api/core/groups/" + group + "/members").with(asha).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ravi\",\"email\":\"" + raviEmail + "\"}")).andReturn().getResponse().getContentAsString();
        ashaMember = ((List<?>) JsonPath.read(members, "$.members[?(@.you == true)].id")).getFirst().toString();
        raviMember = ((List<?>) JsonPath.read(members, "$.members[?(@.name == 'Ravi')].id")).getFirst().toString();
    }

    @Test
    void anExpenseReachesTheOthersInboxAndEmailNotTheOneWhoAddedIt() throws Exception {
        mvc.perform(get("/api/core/me/inbox").with(ravi)).andExpect(jsonPath("$.items[0].title").value(org.hamcrest.Matchers.containsString("added you to Goa trip")));
        long before = unread(ravi);
        long ashaBefore = unread(asha);

        mvc.perform(post("/api/core/groups/" + group + "/expenses").with(asha).contentType(MediaType.APPLICATION_JSON).content("""
                {"description":"Dinner","amount":240000,"occurredOn":"2026-10-05","paidBy":[{"memberId":"%s","value":240000}],
                 "split":"EQUAL","shares":[{"memberId":"%s"},{"memberId":"%s"}]}
                """.formatted(ashaMember, ashaMember, raviMember))).andExpect(status().isCreated());

        assertThat(unread(ravi)).isEqualTo(before + 1);
        assertThat(unread(asha)).isEqualTo(ashaBefore);
        mvc.perform(get("/api/core/me/inbox").with(ravi))
                .andExpect(jsonPath("$.items[0].kind").value("GROUP_EXPENSE"))
                .andExpect(jsonPath("$.items[0].title").value(org.hamcrest.Matchers.containsString("added Dinner (₹2,400) in Goa trip")))
                .andExpect(jsonPath("$.items[0].body").value("Your share is ₹1,200."))
                .andExpect(jsonPath("$.items[0].link").value("/groups/" + group));
        assertThat(jdbc.sql("select count(*) from email_outbox where to_email = ? and subject like '%added Dinner%'")
                .param(raviEmail).query(Long.class).single()).isEqualTo(1);

        // A payment reaches both sides, except whoever recorded it.
        mvc.perform(post("/api/core/groups/" + group + "/settlements").with(asha).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromMemberId\":\"%s\",\"toMemberId\":\"%s\",\"amount\":120000,\"occurredOn\":\"2026-10-06\"}".formatted(raviMember, ashaMember)))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/core/me/inbox").with(ravi)).andExpect(jsonPath("$.items[0].kind").value("SETTLEMENT"))
                .andExpect(jsonPath("$.items[0].title").value("Ravi paid " + memberName(ashaMember) + " ₹1,200 in Goa trip"));
    }

    @Test
    void readingIsPerPersonAndNobodyCanMarkSomeoneElsesItems() throws Exception {
        String items = mvc.perform(get("/api/core/me/inbox").with(ravi)).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(items, "$.items[0].id");
        // Asha sends Ravi's item id: nothing of Ravi's changes.
        mvc.perform(post("/api/core/me/inbox/read").with(asha).contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[\"" + id + "\"]}"))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("select read_at is null from inbox where id = ?").param(UUID.fromString(id)).query(Boolean.class).single()).isTrue();

        mvc.perform(post("/api/core/me/inbox/read").with(ravi).contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[\"" + id + "\"]}"))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("select read_at is not null from inbox where id = ?").param(UUID.fromString(id)).query(Boolean.class).single()).isTrue();
        mvc.perform(post("/api/core/me/inbox/read").with(ravi).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.unread").value(0));
        mvc.perform(get("/api/core/me/inbox/unread").with(ravi)).andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    void someoneJoiningByLinkIsAnnouncedToTheGroup() throws Exception {
        String token = JsonPath.read(mvc.perform(get("/api/core/groups/" + group + "/link").with(asha)).andReturn().getResponse().getContentAsString(), "$.token");
        var meera = user(unique() + "@example.com", UUID.randomUUID().toString(), "s-" + UUID.randomUUID());
        mvc.perform(post("/api/core/groups/join/" + token).with(meera)).andExpect(status().isOk());
        mvc.perform(get("/api/core/me/inbox").with(asha)).andExpect(jsonPath("$.items[0].kind").value("GROUP_MEMBER"))
                .andExpect(jsonPath("$.items[0].title").value(org.hamcrest.Matchers.endsWith("joined Goa trip")));
    }

    @Test
    void aSignInIsRecordedOncePerSessionAndChangesAreOnRecordWithoutTheirContent() throws Exception {
        String session = "s-" + UUID.randomUUID();
        String email = unique() + "@example.com";
        String subject = UUID.randomUUID().toString();
        var neha = user(email, subject, session);
        String id = JsonPath.read(mvc.perform(get("/api/core/me").with(neha).header("User-Agent", "Mozilla/5.0 (Windows NT 10.0) Chrome/130.0"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(get("/api/core/me").with(neha));
        mvc.perform(get("/api/core/plan").with(neha));
        assertThat(events(id, "SIGN_IN")).isEqualTo(1);
        mvc.perform(get("/api/core/me/inbox").with(neha)).andExpect(jsonPath("$.items[0].title").value("New sign-in on Chrome on Windows"));
        // A new session is a new sign-in on record; from the same browser it isn't announced again, from a new one it is.
        mvc.perform(get("/api/core/me").with(user(email, subject, "s-" + UUID.randomUUID())).header("User-Agent", "Mozilla/5.0 (Windows NT 10.0) Chrome/130.0"));
        assertThat(events(id, "SIGN_IN")).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from inbox where user_id = ? and kind = 'SIGN_IN'").param(UUID.fromString(id)).query(Long.class).single()).isEqualTo(1);
        mvc.perform(get("/api/core/me").with(user(email, subject, "s-" + UUID.randomUUID())).header("User-Agent", "Mozilla/5.0 (iPhone) Safari/604.1"));
        mvc.perform(get("/api/core/me/inbox").with(neha)).andExpect(jsonPath("$.items[0].title").value("New sign-in on Safari on iOS"));

        String space = JsonPath.read(mvc.perform(get("/api/core/me").with(neha)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
        mvc.perform(post("/api/core/spaces/" + space + "/accounts").with(neha).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"BANK\",\"name\":\"Private savings 4242\"}")).andExpect(status().isCreated());
        mvc.perform(delete("/api/core/spaces/" + space + "/categories/" + UUID.randomUUID()).with(neha)).andExpect(status().isNotFound());

        String activity = mvc.perform(get("/api/core/admin/users/" + id + "/activity").with(OWNER)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(activity).contains("POST /api/core/spaces/{spaceId}/accounts").contains("\"status\":201")
                .contains("DELETE /api/core/spaces/{spaceId}/categories/{categoryId}").contains("\"status\":404")
                .contains(space).doesNotContain("Private savings");
        mvc.perform(get("/api/core/admin/activity").param("type", "SIGN_IN").with(OWNER)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("SIGN_IN"));
        mvc.perform(get("/api/core/admin/activity").with(neha)).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- helpers

    private String memberName(String memberId) {
        return jdbc.sql("select display_name from group_members where id = ?").param(UUID.fromString(memberId)).query(String.class).single();
    }

    private long unread(RequestPostProcessor who) throws Exception {
        return ((Number) JsonPath.read(mvc.perform(get("/api/core/me/inbox/unread").with(who)).andReturn().getResponse().getContentAsString(), "$.unread")).longValue();
    }

    private long events(String userId, String type) {
        return jdbc.sql("select count(*) from user_events where user_id = ? and type = ?").param(UUID.fromString(userId)).param(type)
                .query(Long.class).single();
    }

    private static String unique() {
        return "i" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static RequestPostProcessor user(String email, String subject, String session) {
        return jwt().jwt(j -> j.subject(subject).claim("email", email).claim("email_verified", true).claim("sid", session));
    }
}
