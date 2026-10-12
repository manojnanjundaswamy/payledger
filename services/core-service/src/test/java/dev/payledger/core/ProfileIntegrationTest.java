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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ProfileIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void firstRequestCreatesTheUserWithIndianDefaultsAndAPersonalSpace() throws Exception {
        String subject = UUID.randomUUID().toString();
        String email = "Priya." + subject.substring(0, 8) + "@Example.com";

        String body = mvc.perform(get("/api/core/me").with(user(subject, email, "Priya Sharma")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.displayName").value("Priya Sharma"))
                .andExpect(jsonPath("$.settings.locale").value("en-IN"))
                .andExpect(jsonPath("$.settings.timeZone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.settings.currency").value("INR"))
                .andExpect(jsonPath("$.settings.budgetStartDay").value(1))
                .andExpect(jsonPath("$.preferences").isMap())
                .andExpect(jsonPath("$.spaces.length()").value(1))
                .andExpect(jsonPath("$.spaces[0].kind").value("PERSONAL"))
                .andExpect(jsonPath("$.spaces[0].role").value("OWNER"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        mvc.perform(get("/api/core/me").with(user(subject, email, "Priya Sharma")))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.spaces.length()").value(1));
    }

    @Test
    void concurrentFirstRequestsCreateExactlyOneUserAndOneSpace() throws Exception {
        String subject = UUID.randomUUID().toString();
        String email = subject + "@example.com";

        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                results.add(pool.submit(() -> mvc.perform(get("/api/core/me").with(user(subject, email, "Race")))
                        .andReturn().getResponse().getStatus()));
            }
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(200);
            }
        }

        assertThat(jdbc.sql("select count(*) from users where subject = ?").param(subject).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from spaces s join users u on u.id = s.created_by where u.subject = ?")
                .param(subject).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void settingsAndPreferencesCanBeChangedAndAreValidated() throws Exception {
        String subject = UUID.randomUUID().toString();
        RequestPostProcessor me = user(subject, subject + "@example.com", "Arjun");

        mvc.perform(patch("/api/core/me").with(me).contentType(MediaType.APPLICATION_JSON).content("""
                        {"displayName":"Arjun K","budgetStartDay":25,
                         "preferences":{"theme":"dark","accent":"#0f766e","density":"compact","dashboard":["safeToSpend","budgets"]}}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Arjun K"))
                .andExpect(jsonPath("$.settings.budgetStartDay").value(25))
                .andExpect(jsonPath("$.settings.currency").value("INR"))
                .andExpect(jsonPath("$.preferences.theme").value("dark"))
                .andExpect(jsonPath("$.preferences.dashboard[1]").value("budgets"));

        mvc.perform(patch("/api/core/me").with(me).contentType(MediaType.APPLICATION_JSON).content("{\"budgetStartDay\":29}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/core/me").with(me).contentType(MediaType.APPLICATION_JSON).content("{\"timeZone\":\"Mars/Base\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/core/me").with(me).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferences\":{\"blob\":\"" + "x".repeat(20_000) + "\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("preferences-too-large"));

        mvc.perform(get("/api/core/me").with(me)).andExpect(jsonPath("$.preferences.theme").value("dark"));
    }

    @Test
    void requestsWithoutATokenOrAVerifiedEmailAreRefused() throws Exception {
        mvc.perform(get("/api/core/me")).andExpect(status().isUnauthorized());

        String subject = UUID.randomUUID().toString();
        mvc.perform(get("/api/core/me").with(jwt().jwt(j -> j.subject(subject)
                        .claim("email", subject + "@example.com").claim("email_verified", false))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("email-not-verified"));
    }

    private static RequestPostProcessor user(String subject, String email, String name) {
        return jwt().jwt(j -> j.subject(subject).claim("email", email).claim("email_verified", true).claim("name", name));
    }
}
