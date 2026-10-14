package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Your data, your control: download everything, or delete the account. A shared group keeps the history with you as a
 * placeholder; a group only you joined goes; the sign-in is removed too. Keycloak is faked.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DataRightsIntegrationTest {

    @DynamicPropertySource
    static void keycloak(DynamicPropertyRegistry registry) {
        registry.add("payledger.identity.url", FakeKeycloak::url);
        registry.add("payledger.identity.client-secret", () -> FakeKeycloak.SECRET);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void youCanDownloadEverythingAndDeleteTheAccountWithoutBreakingFriendsGroups() throws Exception {
        String raviEmail = UUID.randomUUID() + "@example.com";
        String raviSubject = UUID.randomUUID().toString();
        var ravi = user(raviEmail, raviSubject);
        var asha = user(UUID.randomUUID() + "@example.com", UUID.randomUUID().toString());
        String space = JsonPath.read(body(get("/api/core/me"), ravi), "$.spaces[0].id");
        UUID raviId = jdbc.sql("select id from users where subject = ?").param(raviSubject).query(UUID.class).single();

        String bank = JsonPath.read(send(post("/api/core/spaces/" + space + "/accounts"), ravi,
                "{\"kind\":\"BANK\",\"name\":\"HDFC\",\"openingBalance\":0,\"openingDate\":\"2026-01-01\"}"), "$.id");
        String categories = body(get("/api/core/spaces/" + space + "/categories"), ravi);
        String food = JsonPath.<List<String>>read(categories, "$[?(@.name == 'Groceries')].id").getFirst();
        send(post("/api/core/spaces/" + space + "/transactions"), ravi,
                "{\"kind\":\"EXPENSE\",\"amount\":12300,\"accountId\":\"" + bank + "\",\"categoryId\":\"" + food + "\",\"occurredOn\":\"2026-10-05\",\"description\":\"Secret biryani\"}");
        send(post("/api/core/spaces/" + space + "/goals"), ravi, "{\"name\":\"Goa fund\",\"target\":100000}");

        // A group Asha made, with Ravi in it, and a group only Ravi joined.
        String shared = JsonPath.read(send(post("/api/core/groups"), asha, "{\"name\":\"Flat 302\"}"), "$.id");
        send(post("/api/core/groups/" + shared + "/members"), asha, "{\"name\":\"Ravi\",\"email\":\"" + raviEmail + "\"}");
        body(get("/api/core/me"), ravi);
        String members = body(get("/api/core/groups/" + shared), ravi);
        String raviMember = JsonPath.<List<String>>read(members, "$.members[?(@.you == true)].id").getFirst();
        String ashaMember = JsonPath.<List<String>>read(members, "$.members[?(@.you == false)].id").getFirst();
        send(post("/api/core/groups/" + shared + "/expenses"), ravi, """
                {"description":"Groceries","amount":60000,"occurredOn":"2026-10-05","paidBy":[{"memberId":"%s","value":60000}],
                 "split":"EQUAL","shares":[{"memberId":"%s"},{"memberId":"%s"}]}
                """.formatted(raviMember, raviMember, ashaMember));
        String solo = JsonPath.read(send(post("/api/core/groups"), ravi, "{\"name\":\"Just me\"}"), "$.id");

        // The download has it all.
        String export = mvc.perform(get("/api/core/me/export").with(ravi)).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment; filename=\"payledger-my-data-")))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(export, "$.profile[0].email")).isEqualTo(raviEmail);
        assertThat(JsonPath.<List<String>>read(export, "$.transactions[*].description")).contains("Secret biryani");
        assertThat(JsonPath.<List<String>>read(export, "$.goals[*].name")).containsExactly("Goa fund");
        assertThat(JsonPath.<List<String>>read(export, "$.groupExpenses[*].description")).containsExactly("Groceries");
        // Nobody else's private data: Asha shows in the shared group only by her name there.
        assertThat(export).doesNotContain(JsonPath.<String>read(body(get("/api/core/me"), asha), "$.email"));

        // Deleting needs the email typed back.
        mvc.perform(post("/api/core/me/delete").with(ravi).contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":\"yes\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("confirm-email"));
        mvc.perform(post("/api/core/me/delete").with(ravi).contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":\" " + raviEmail.toUpperCase() + " \"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.sql("select count(*) from users where id = ?").param(raviId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from spaces where id = ?::uuid").param(space).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from transactions where description = 'Secret biryani' and space_id = ?::uuid").param(space).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from groups where id = ?::uuid").param(solo).query(Long.class).single()).isZero();
        assertThat(FakeKeycloak.callsFor(raviSubject)).anyMatch(c -> c.startsWith("DELETE /admin/realms/payledger/users/" + raviSubject));

        // Asha's group keeps its history: Ravi is a placeholder, the expense and balances stand.
        mvc.perform(get("/api/core/groups/" + shared).with(asha)).andExpect(status().isOk())
                .andExpect(jsonPath("$.members[?(@.name == 'Ravi')].joined").value(org.hamcrest.Matchers.contains(false)));
        mvc.perform(get("/api/core/groups/" + shared + "/activity").with(asha)).andExpect(jsonPath("$.expenses.length()").value(1));
    }

    private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, RequestPostProcessor who) throws Exception {
        return mvc.perform(request.with(who)).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private String send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, RequestPostProcessor who, String json) throws Exception {
        return body(request.contentType(MediaType.APPLICATION_JSON).content(json), who);
    }

    private static RequestPostProcessor user(String email, String subject) {
        return jwt().jwt(j -> j.subject(subject).claim("email", email).claim("email_verified", true));
    }
}
