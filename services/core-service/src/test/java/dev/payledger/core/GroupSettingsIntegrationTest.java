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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The owner's switches: the join link, guests, and who may change an entry. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GroupSettingsIntegrationTest {

    @DynamicPropertySource
    static void ledgerUrl(DynamicPropertyRegistry registry) {
        registry.add("payledger.ledger.url", FakeLedger::url);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void theOwnersSwitchesAreObeyed() throws Exception {
        FakeLedger.DOWN.set(false);
        RequestPostProcessor owner = user("Asha");
        RequestPostProcessor friend = user("Ravi");
        String group = JsonPath.read(ok(post("/api/core/groups").with(owner), "{\"name\":\"Flat\"}"), "$.id");
        String token = JsonPath.read(mvc.perform(get("/api/core/groups/" + group + "/link").with(owner)).andReturn().getResponse().getContentAsString(), "$.token");
        String joined = ok(post("/api/core/groups/join/" + token).with(friend), "");
        String ashaM = JsonPath.<List<String>>read(joined, "$.members[?(@.name == 'Asha')].id").getFirst();
        String raviM = JsonPath.<List<String>>read(joined, "$.members[?(@.name == 'Ravi')].id").getFirst();
        mvc.perform(get("/api/core/groups/" + group).with(owner))
                .andExpect(jsonPath("$.role").value("OWNER")).andExpect(jsonPath("$.settings.guestsAllowed").value(true))
                .andExpect(jsonPath("$.settings.ownEditsOnly").value(false));

        // Only the owner changes settings.
        mvc.perform(patch("/api/core/groups/" + group).with(friend).contentType(MediaType.APPLICATION_JSON).content("{\"guestsAllowed\":false}"))
                .andExpect(jsonPath("$.code").value("owner-only"));

        // A guest joins while it's allowed...
        String email = "g-" + UUID.randomUUID() + "@example.com";
        String codeId = JsonPath.read(ok(post("/api/guest/links/" + token + "/code"), "{\"name\":\"Dev\",\"email\":\"" + email + "\"}"), "$.codeId");
        String code = jdbc.sql("select subject from email_outbox where to_email = :e order by created_at desc limit 1").param("e", email).query(String.class).single().substring(0, 6);
        Cookie guest = mvc.perform(post("/api/guest/codes/" + codeId).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"))
                .andReturn().getResponse().getCookie("pl_guest");
        mvc.perform(get("/api/guest/groups/" + group).cookie(guest)).andExpect(status().isOk());
        // ...and once guests are switched off, no new ones, and the existing one is out.
        ok(patch("/api/core/groups/" + group).with(owner), "{\"guestsAllowed\":false}");
        mvc.perform(get("/api/guest/links/" + token)).andExpect(jsonPath("$.guests").value(false)).andExpect(jsonPath("$.spots.length()").value(0));
        mvc.perform(post("/api/guest/links/" + token + "/code").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Kiran\",\"email\":\"k@example.com\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("guests-off"));
        mvc.perform(get("/api/guest/groups/" + group).cookie(guest)).andExpect(status().isNotFound());

        // The join link switched off stops working for everyone, and on again works.
        ok(patch("/api/core/groups/" + group).with(owner), "{\"linkOpen\":false}");
        mvc.perform(get("/api/core/groups/join/" + token).with(user("Someone"))).andExpect(jsonPath("$.code").value("invalid-link"));
        ok(patch("/api/core/groups/" + group).with(owner), "{\"linkOpen\":true}");
        mvc.perform(get("/api/core/groups/join/" + token).with(user("Someone"))).andExpect(status().isOk());

        // Only the person who added it, or the owner, changes an entry.
        ok(patch("/api/core/groups/" + group).with(owner), "{\"ownEditsOnly\":true}");
        String ashas = JsonPath.read(ok(post("/api/core/groups/" + group + "/expenses").with(owner), expense("Rent", ashaM, ashaM, raviM)), "$.id");
        String ravis = JsonPath.read(ok(post("/api/core/groups/" + group + "/expenses").with(friend), expense("Milk", raviM, ashaM, raviM)), "$.id");
        mvc.perform(delete("/api/core/groups/" + group + "/expenses/" + ashas).param("version", "1").with(friend))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("not-yours"));
        mvc.perform(delete("/api/core/groups/" + group + "/expenses/" + ravis).param("version", "1").with(owner)).andExpect(status().isNoContent());
        ok(patch("/api/core/groups/" + group).with(owner), "{\"ownEditsOnly\":false}");
        mvc.perform(delete("/api/core/groups/" + group + "/expenses/" + ashas).param("version", "1").with(friend)).andExpect(status().isNoContent());

        // Every change is in the group's history.
        mvc.perform(get("/api/core/groups/" + group + "/history").with(owner))
                .andExpect(jsonPath("$[?(@.action == 'GROUP_UPDATED')]").isNotEmpty());
    }

    private static String expense(String what, String payer, String a, String b) {
        return """
                {"description":"%s","amount":10000,"occurredOn":"2026-10-05","paidBy":[{"memberId":"%s","value":10000}],"split":"EQUAL","shares":[{"memberId":"%s"},{"memberId":"%s"}]}
                """.formatted(what, payer, a, b);
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
