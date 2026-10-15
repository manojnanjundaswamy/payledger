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

/** Comments on a group expense: the others in it hear about it, and only the writer can delete one. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GroupCommentsIntegrationTest {

    @DynamicPropertySource
    static void ledgerUrl(DynamicPropertyRegistry registry) {
        registry.add("payledger.ledger.url", FakeLedger::url);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void commentsReachTheOthersAndOnlyTheirWriterDeletesThem() throws Exception {
        FakeLedger.DOWN.set(false);
        RequestPostProcessor asha = user("Asha");
        String ravisEmail = UUID.randomUUID() + "@example.com";
        String raviSubject = UUID.randomUUID().toString();
        RequestPostProcessor ravi = jwt().jwt(j -> j.subject(raviSubject).claim("email", ravisEmail).claim("email_verified", true).claim("name", "Ravi"));
        mvc.perform(get("/api/core/me").with(ravi));
        UUID raviId = jdbc.sql("select id from users where email = :e").param("e", ravisEmail).query(UUID.class).single();

        String group = JsonPath.read(ok(post("/api/core/groups").with(asha), "{\"name\":\"Goa\"}"), "$.id");
        String members = ok(post("/api/core/groups/" + group + "/members").with(asha), "{\"name\":\"Ravi\",\"email\":\"" + ravisEmail + "\"}");
        String ashaM = JsonPath.<List<String>>read(members, "$.members[?(@.name == 'Asha')].id").getFirst();
        String raviM = JsonPath.<List<String>>read(members, "$.members[?(@.name == 'Ravi')].id").getFirst();
        String expense = JsonPath.read(ok(post("/api/core/groups/" + group + "/expenses").with(asha), """
                {"description":"Dinner","amount":100000,"occurredOn":"2026-10-05","paidBy":[{"memberId":"%s","value":100000}],"split":"EQUAL","shares":[{"memberId":"%s"},{"memberId":"%s"}]}
                """.formatted(ashaM, ashaM, raviM)), "$.id");

        String comment = JsonPath.read(ok(post("/api/core/groups/" + group + "/expenses/" + expense + "/comments").with(asha),
                "{\"body\":\"Was this Friday's dinner?\"}"), "$.id");
        assertThat(jdbc.sql("select title from inbox where user_id = :u order by created_at desc limit 1").param("u", raviId).query(String.class).single())
                .isEqualTo("Asha commented on Dinner in Goa");

        mvc.perform(get("/api/core/groups/" + group + "/comments").with(ravi))
                .andExpect(jsonPath("$[0].body").value("Was this Friday's dinner?")).andExpect(jsonPath("$[0].you").value(false))
                .andExpect(jsonPath("$[0].name").value("Asha"));
        mvc.perform(delete("/api/core/groups/" + group + "/comments/" + comment).with(ravi))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("not-yours"));
        mvc.perform(post("/api/core/groups/" + group + "/expenses/" + UUID.randomUUID() + "/comments").with(asha)
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"x\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/core/groups/" + group + "/expenses/" + expense + "/comments").with(asha)
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"  \"}")).andExpect(jsonPath("$.code").value("invalid-comment"));
        mvc.perform(delete("/api/core/groups/" + group + "/comments/" + comment).with(asha)).andExpect(status().isNoContent());
        mvc.perform(get("/api/core/groups/" + group + "/comments").with(asha)).andExpect(jsonPath("$.length()").value(0));
    }

    private static RequestPostProcessor user(String name) {
        String subject = UUID.randomUUID().toString();
        return jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true).claim("name", name));
    }

    private String ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }
}
