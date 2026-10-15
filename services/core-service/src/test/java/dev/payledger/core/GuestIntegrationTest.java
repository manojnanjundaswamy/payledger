package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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

/**
 * A guest without an account: opens the link, proves the email with a code, adds an expense and settles up; sees only
 * their groups; changes only what they added; and becomes an ordinary member on signing up with that email.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GuestIntegrationTest {

    @DynamicPropertySource
    static void ledgerUrl(DynamicPropertyRegistry registry) {
        registry.add("payledger.ledger.url", FakeLedger::url);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void aGuestJoinsByLinkAddsAnExpenseAndSettlesUp() throws Exception {
        FakeLedger.DOWN.set(false);
        String subject = UUID.randomUUID().toString();
        String ownerEmail = subject + "@example.com";
        RequestPostProcessor owner = jwt().jwt(j -> j.subject(subject).claim("email", ownerEmail).claim("email_verified", true).claim("name", "Priya"));
        mvc.perform(get("/api/core/me").with(owner));
        String group = JsonPath.read(body(post("/api/core/groups").with(owner), "{\"name\":\"Goa\"}"), "$.id");
        String withRavi = body(post("/api/core/groups/" + group + "/members").with(owner), "{\"name\":\"Ravi\"}");
        String ravi = JsonPath.<List<String>>read(withRavi, "$.members[?(@.name == 'Ravi')].id").getFirst();
        String priya = JsonPath.<List<String>>read(withRavi, "$.members[?(@.name == 'Priya')].id").getFirst();
        String token = JsonPath.read(mvc.perform(get("/api/core/groups/" + group + "/link").with(owner)).andReturn().getResponse().getContentAsString(), "$.token");

        // The link shows the group and Ravi's waiting spot, without signing in.
        mvc.perform(get("/api/guest/links/" + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.groupName").value("Goa")).andExpect(jsonPath("$.spots[0].name").value("Ravi"));
        // Someone with an account signs in instead.
        mvc.perform(post("/api/guest/links/" + token + "/code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Me\",\"email\":\"" + ownerEmail + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("has-account"));

        String email = "ravi-" + UUID.randomUUID() + "@example.com";
        String codeId = JsonPath.read(body(post("/api/guest/links/" + token + "/code"), "{\"memberId\":\"" + ravi + "\",\"email\":\"" + email + "\"}"), "$.codeId");
        String code = codeFor(email);
        // Guessing runs out: five wrong tries and the code is dead, even the right one.
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/guest/codes/" + codeId).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + wrong + "\"}"))
                    .andExpect(jsonPath("$.code").value("wrong-code"));
        }
        mvc.perform(post("/api/guest/codes/" + codeId).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"))
                .andExpect(jsonPath("$.code").value("code-expired"));

        codeId = JsonPath.read(body(post("/api/guest/links/" + token + "/code"), "{\"memberId\":\"" + ravi + "\",\"email\":\"" + email + "\"}"), "$.codeId");
        var verified = mvc.perform(post("/api/guest/codes/" + codeId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + codeFor(email) + "\"}")).andExpect(status().isOk()).andReturn().getResponse();
        Cookie session = verified.getCookie("pl_guest");
        assertThat(session).isNotNull();
        assertThat(session.isHttpOnly()).isTrue();
        assertThat(verified.getContentAsString()).doesNotContain(session.getValue());

        mvc.perform(get("/api/guest/me").cookie(session)).andExpect(jsonPath("$.groups[0].id").value(group))
                .andExpect(jsonPath("$.groups[0].you").value(ravi));
        mvc.perform(get("/api/guest/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("guest-signed-out"));
        mvc.perform(get("/api/guest/groups/" + group).cookie(session)).andExpect(jsonPath("$.you").value(ravi));

        // Ravi paid ₹1,200 for both; the owner sees it.
        String expense = JsonPath.read(guest(post("/api/guest/groups/" + group + "/expenses"), session, """
                {"description":"Dinner","amount":120000,"occurredOn":"2026-10-05","paidBy":[{"memberId":"%s","value":120000}],"split":"EQUAL","shares":[{"memberId":"%s"},{"memberId":"%s"}]}
                """.formatted(ravi, ravi, priya)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(get("/api/core/groups/" + group + "/activity").with(owner)).andExpect(jsonPath("$.expenses[0].description").value("Dinner"));
        mvc.perform(get("/api/core/groups/" + group + "/history").with(owner))
                .andExpect(jsonPath("$[0].action").value("EXPENSE_ADDED")).andExpect(jsonPath("$[0].actorName").value("Ravi"));

        // Priya pays Ravi back; the guest records it.
        guest(post("/api/guest/groups/" + group + "/settlements"), session, """
                {"fromMemberId":"%s","toMemberId":"%s","amount":60000,"occurredOn":"2026-10-06"}
                """.formatted(priya, ravi)).andExpect(status().isCreated());

        // Guests change only what they added.
        String ownersExpense = JsonPath.read(body(post("/api/core/groups/" + group + "/expenses").with(owner), """
                {"description":"Taxi","amount":40000,"occurredOn":"2026-10-05","paidBy":[{"memberId":"%s","value":40000}],"split":"EQUAL","shares":[{"memberId":"%s"},{"memberId":"%s"}]}
                """.formatted(priya, ravi, priya)), "$.id");
        mvc.perform(delete("/api/guest/groups/" + group + "/expenses/" + ownersExpense).param("version", "1").cookie(session))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("not-yours"));
        mvc.perform(delete("/api/guest/groups/" + group + "/expenses/" + expense).param("version", "1").cookie(session))
                .andExpect(status().isNoContent());

        // Another group is out of reach.
        String other = JsonPath.read(body(post("/api/core/groups").with(owner), "{\"name\":\"Flat\"}"), "$.id");
        mvc.perform(get("/api/guest/groups/" + other).cookie(session)).andExpect(status().isNotFound());

        // Ravi signs up with that email: the spot is his account's now, and the guest session no longer reaches it.
        RequestPostProcessor raviAccount = jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("email", email).claim("email_verified", true).claim("name", "Ravi K"));
        mvc.perform(get("/api/core/me").with(raviAccount));
        mvc.perform(get("/api/core/groups/" + group).with(raviAccount)).andExpect(status().isOk()).andExpect(jsonPath("$.you").value(ravi));
        mvc.perform(get("/api/guest/groups/" + group).cookie(session)).andExpect(status().isNotFound());

        // Signing out ends the session.
        mvc.perform(post("/api/guest/sign-out").cookie(session)).andExpect(status().isNoContent());
        mvc.perform(get("/api/guest/me").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void aNewNameJoinsAndInvitedEmailsMustMatch() throws Exception {
        String subject = UUID.randomUUID().toString();
        RequestPostProcessor owner = jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true).claim("name", "Asha"));
        mvc.perform(get("/api/core/me").with(owner));
        String group = JsonPath.read(body(post("/api/core/groups").with(owner), "{\"name\":\"Trek\"}"), "$.id");
        String invited = "kiran-" + UUID.randomUUID() + "@example.com";
        String withKiran = body(post("/api/core/groups/" + group + "/members").with(owner), "{\"name\":\"Kiran\",\"email\":\"" + invited + "\"}");
        String kiran = JsonPath.<List<String>>read(withKiran, "$.members[?(@.name == 'Kiran')].id").getFirst();
        String token = JsonPath.read(mvc.perform(get("/api/core/groups/" + group + "/link").with(owner)).andReturn().getResponse().getContentAsString(), "$.token");

        // Kiran's spot was invited with an email: someone else can't take it.
        mvc.perform(post("/api/guest/links/" + token + "/code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"" + kiran + "\",\"email\":\"someone-else@example.com\"}"))
                .andExpect(jsonPath("$.code").value("email-mismatch"));

        // A new name: added as a member under that name.
        String email = "dev-" + UUID.randomUUID() + "@example.com";
        String codeId = JsonPath.read(body(post("/api/guest/links/" + token + "/code"), "{\"name\":\"Dev\",\"email\":\"" + email + "\"}"), "$.codeId");
        Cookie session = mvc.perform(post("/api/guest/codes/" + codeId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + codeFor(email) + "\"}")).andReturn().getResponse().getCookie("pl_guest");
        String seen = mvc.perform(get("/api/guest/groups/" + group).cookie(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Boolean>>read(seen, "$.members[?(@.name == 'Dev')].you")).as(seen).containsExactly(true);
        mvc.perform(get("/api/core/groups/" + group + "/history").with(owner))
                .andExpect(jsonPath("$[0].action").value("MEMBER_JOINED")).andExpect(jsonPath("$[0].details.via").value("link, as a guest"));
    }

    /** The code, from the email waiting in the outbox (the only place it is in plain text). */
    private String codeFor(String email) {
        String subject = jdbc.sql("select subject from email_outbox where to_email = :e order by created_at desc limit 1")
                .param("e", email).query(String.class).single();
        return subject.substring(0, 6);
    }

    private String body(MockHttpServletRequestBuilder request, String json) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    private ResultActions guest(MockHttpServletRequestBuilder request, Cookie session, String json) throws Exception {
        return mvc.perform(request.cookie(session).contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
