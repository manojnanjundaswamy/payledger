package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Settings staff change (Free's limits, the trial, the announcement) and who is staff. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "payledger.admin.owners=founder@payledger.example")
@AutoConfigureMockMvc
class AdminSettingsIntegrationTest {

    static final RequestPostProcessor OWNER = user("founder@payledger.example", UUID.randomUUID().toString());
    static final String DEFAULTS = settings(5, 30, null);

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void restoreDefaults() throws Exception {
        // The database is shared with the other test classes, which expect the built-in plan.
        save(OWNER, DEFAULTS).andExpect(status().isOk());
    }

    @Test
    void freeLimitsCanBeChangedAndApplyToTheNextAdd() throws Exception {
        mvc.perform(get("/api/core/admin/settings").with(OWNER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.freeLimits.accounts").value(5)).andExpect(jsonPath("$.trialDays").value(30));
        save(OWNER, settings(2, 30, null)).andExpect(status().isOk()).andExpect(jsonPath("$.freeLimits.accounts").value(2));

        var customer = newUser();
        String space = JsonPath.read(mvc.perform(get("/api/core/me").with(customer)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
        mvc.perform(get("/api/core/plan").with(customer)).andExpect(jsonPath("$.limits.accounts").value(2));
        account(customer, space, "One").andExpect(status().isCreated());
        account(customer, space, "Two").andExpect(status().isCreated());
        account(customer, space, "Three").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("the Free plan allows 2 open accounts; Premium has no limit"));

        mvc.perform(get("/api/core/admin/audit").with(OWNER)).andExpect(jsonPath("$[0].action").value("settings.update"))
                .andExpect(jsonPath("$[0].details.before.freeLimits.accounts").value(5))
                .andExpect(jsonPath("$[0].details.after.freeLimits.accounts").value(2));
        save(OWNER, settings(-1, 30, null)).andExpect(status().isBadRequest());
    }

    @Test
    void theTrialCanBeLengthenedOrTurnedOff() throws Exception {
        save(OWNER, settings(5, 0, null)).andExpect(status().isOk());
        var customer = newUser();
        mvc.perform(get("/api/core/plan").with(customer)).andExpect(jsonPath("$.trialAvailable").value(false));
        mvc.perform(post("/api/core/plan/trial").with(customer)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("trial-off"));

        save(OWNER, settings(5, 60, null)).andExpect(status().isOk());
        mvc.perform(post("/api/core/plan/trial").with(customer)).andExpect(status().isOk());
        String until = JsonPath.read(mvc.perform(get("/api/core/plan").with(customer)).andReturn().getResponse().getContentAsString(), "$.premiumUntil");
        org.assertj.core.api.Assertions.assertThat(java.time.Instant.parse(until))
                .isAfter(java.time.Instant.now().plus(java.time.Duration.ofDays(59)));
    }

    @Test
    void everyoneSeesTheAnnouncementUntilItsLastDay() throws Exception {
        var customer = newUser();
        mvc.perform(get("/api/core/announcement").with(customer)).andExpect(status().isNoContent());
        save(OWNER, settings(5, 30, "{\"message\":\"Maintenance on Sunday, 2-3 am\",\"tone\":\"WARNING\",\"until\":\"" + LocalDate.now().plusDays(3) + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/core/announcement").with(customer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Maintenance on Sunday, 2-3 am")).andExpect(jsonPath("$.tone").value("WARNING"));
        save(OWNER, settings(5, 30, "{\"message\":\"Old news\",\"tone\":\"INFO\",\"until\":\"" + LocalDate.now().minusDays(2) + "\"}"));
        mvc.perform(get("/api/core/announcement").with(customer)).andExpect(status().isNoContent());
        save(OWNER, settings(5, 30, "{\"message\":\"x\",\"tone\":\"LOUD\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void onlyOwnersManageStaffAndNeverThemselvesOrAConfiguredOwner() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        var person = user(email, UUID.randomUUID().toString());
        String personId = JsonPath.read(mvc.perform(get("/api/core/me").with(person)).andReturn().getResponse().getContentAsString(), "$.id");

        grant(OWNER, email, "SUPPORT").andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email == '" + email + "')].role").value(org.hamcrest.Matchers.contains("SUPPORT")))
                .andExpect(jsonPath("$[?(@.email == 'founder@payledger.example')].configured").value(org.hamcrest.Matchers.contains(true)));
        mvc.perform(get("/api/core/me").with(person)).andExpect(jsonPath("$.staff.role").value("SUPPORT"));
        // Support can't see settings or staff.
        mvc.perform(get("/api/core/admin/settings").with(person)).andExpect(status().isForbidden());
        mvc.perform(get("/api/core/admin/staff").with(person)).andExpect(status().isForbidden());

        grant(OWNER, email, "ADMIN").andExpect(status().isOk());
        mvc.perform(get("/api/core/admin/settings").with(person)).andExpect(status().isOk());
        // An admin still can't manage staff, so can't promote themselves.
        grant(person, email, "OWNER").andExpect(status().isForbidden());

        grant(OWNER, "founder@payledger.example", "SUPPORT").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("self"));
        grant(OWNER, "nobody-" + UUID.randomUUID() + "@example.com", "SUPPORT").andExpect(status().isNotFound());

        mvc.perform(delete("/api/core/admin/staff/" + personId).with(OWNER)).andExpect(status().isNoContent());
        mvc.perform(get("/api/core/me").with(person)).andExpect(jsonPath("$.staff").doesNotExist());
        mvc.perform(get("/api/core/admin/overview").with(person)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("not-staff"));
        mvc.perform(get("/api/core/admin/audit").with(OWNER)).andExpect(jsonPath("$[0].action").value("staff.remove"));

        // A database owner can't change their own role either, nor a configured owner's.
        grant(OWNER, email, "OWNER");
        grant(person, email, "SUPPORT").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("self"));
        grant(person, "founder@payledger.example", "SUPPORT").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("protected-user"));
        mvc.perform(delete("/api/core/admin/staff/" + personId).with(OWNER)).andExpect(status().isNoContent());
    }

    // ---------------------------------------------------------------- helpers

    private static String settings(int accounts, int trialDays, String announcement) {
        return """
                {"freeLimits":{"accounts":%d,"budgets":5,"bills":5,"bigCosts":1,"imports":3},"trialDays":%d,"announcement":%s}
                """.formatted(accounts, trialDays, announcement);
    }

    private ResultActions save(RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(put("/api/core/admin/settings").with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions grant(RequestPostProcessor who, String email, String role) throws Exception {
        return mvc.perform(put("/api/core/admin/staff").with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}"));
    }

    private ResultActions account(RequestPostProcessor who, String space, String name) throws Exception {
        return mvc.perform(post("/api/core/spaces/" + space + "/accounts").with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"BANK\",\"name\":\"" + name + "\"}"));
    }

    private static RequestPostProcessor newUser() {
        return user(UUID.randomUUID() + "@example.com", UUID.randomUUID().toString());
    }

    private static RequestPostProcessor user(String email, String subject) {
        return jwt().jwt(j -> j.subject(subject).claim("email", email).claim("email_verified", true));
    }
}
