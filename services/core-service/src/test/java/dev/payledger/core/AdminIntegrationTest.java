package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The admin console: who gets in, what each role may do, and the dashboard's numbers. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "payledger.admin.owners=Boss@PayLedger.example")
@AutoConfigureMockMvc
class AdminIntegrationTest {

    /** The configured owner; one identity for the whole class (a second subject with this email would be refused). */
    static final RequestPostProcessor OWNER = user("boss@payledger.example", UUID.randomUUID().toString());

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    @Test
    void customersAreTurnedAwayFromEveryAdminEndpointBeforeAnythingRuns() throws Exception {
        var customer = newUser();
        mvc.perform(get("/api/core/me").with(customer)).andExpect(jsonPath("$.staff").doesNotExist());
        // Every admin endpoint, present and future, with any body: always 403 not-staff, never a 400 or 404 that
        // would tell a customer something about the admin API.
        List<String[]> endpoints = mappings.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream()
                        .filter(p -> p.startsWith("/api/core/admin"))
                        .flatMap(p -> (info.getMethodsCondition().getMethods().isEmpty() ? List.of(RequestMethod.GET)
                                : info.getMethodsCondition().getMethods()).stream()
                                .map(m -> new String[]{m.name(), p.replaceAll("\\{[^}]+}", UUID.randomUUID().toString())})))
                .toList();
        assertThat(endpoints).isNotEmpty();
        for (String[] endpoint : endpoints) {
            mvc.perform(request(HttpMethod.valueOf(endpoint[0]), endpoint[1]).with(customer)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"nonsense\":true}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("not-staff"));
        }
    }

    @Test
    void configuredOwnersHaveEveryPermission() throws Exception {
        mvc.perform(get("/api/core/me").with(OWNER))
                .andExpect(jsonPath("$.staff.role").value("OWNER"))
                .andExpect(jsonPath("$.staff.permissions.length()").value(8));
        mvc.perform(get("/api/core/admin/overview").with(OWNER)).andExpect(status().isOk());
    }

    @Test
    void anAnalystSeesTheDashboard() throws Exception {
        var analyst = newUser();
        String id = JsonPath.read(mvc.perform(get("/api/core/me").with(analyst)).andReturn().getResponse().getContentAsString(), "$.id");
        jdbc.sql("insert into staff_roles (user_id, role) values (?, 'ANALYST')").param(UUID.fromString(id)).update();
        mvc.perform(get("/api/core/me").with(analyst))
                .andExpect(jsonPath("$.staff.role").value("ANALYST"))
                .andExpect(jsonPath("$.staff.permissions").value(org.hamcrest.Matchers.contains("ANALYTICS_VIEW")));
        mvc.perform(get("/api/core/admin/overview").param("days", "7").with(analyst))
                .andExpect(status().isOk()).andExpect(jsonPath("$.days.length()").value(7));
        mvc.perform(get("/api/core/admin/overview").param("days", "400").with(analyst)).andExpect(status().isBadRequest());
    }

    @Test
    void theDashboardCountsSignUpsActiveUsersPlansAndAdoption() throws Exception {
        mvc.perform(get("/api/core/me").with(OWNER));
        String before = overview();

        var a = newUser();
        var b = newUser();
        String aSpace = JsonPath.read(mvc.perform(get("/api/core/me").with(a)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
        mvc.perform(get("/api/core/me").with(b));
        // Several requests in a day are one active day.
        mvc.perform(get("/api/core/plan").with(a));
        mvc.perform(post("/api/core/plan/trial").with(b)).andExpect(status().isOk());
        mvc.perform(post("/api/core/spaces/" + aSpace + "/accounts").with(a).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"BANK\",\"name\":\"HDFC\"}")).andExpect(status().isCreated());

        String after = overview();
        assertThat(delta(before, after, "$.users.total")).isEqualTo(2);
        assertThat(delta(before, after, "$.users.newToday")).isEqualTo(2);
        assertThat(delta(before, after, "$.active.daily")).isEqualTo(2);
        assertThat(delta(before, after, "$.active.monthly")).isEqualTo(2);
        assertThat(delta(before, after, "$.plans.trial")).isEqualTo(1);
        assertThat(delta(before, after, "$.plans.free")).isEqualTo(1);
        assertThat(delta(before, after, "$.plans.trialsStarted30Days")).isEqualTo(1);
        assertThat(delta(before, after, "$.adoption.users")).isEqualTo(2);
        assertThat(delta(before, after, "$.adoption.accounts")).isEqualTo(1);
        // Today is the last day of the series; this week's cohort is the last one.
        assertThat(delta(before, after, "$.days[29].signups")).isEqualTo(2);
        assertThat(delta(before, after, "$.days[29].active")).isEqualTo(2);
        assertThat(delta(before, after, "$.cohorts[7].size")).isEqualTo(2);
        String aId = JsonPath.read(mvc.perform(get("/api/core/me").with(a)).andReturn().getResponse().getContentAsString(), "$.id");
        assertThat(jdbc.sql("select count(*) from user_activity_days where user_id = ?").param(UUID.fromString(aId)).query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("select last_active_on is not null from users where id = ?").param(UUID.fromString(aId)).query(Boolean.class).single())
                .isTrue();
    }

    @Test
    void complimentaryPremiumIsCountedSeparately() throws Exception {
        String overview = overview();
        assertThat((Integer) JsonPath.read(overview, "$.plans.paid")).isGreaterThanOrEqualTo(0);
        assertThat((Integer) JsonPath.read(overview, "$.plans.complimentary")).isGreaterThanOrEqualTo(0);
        List<Double> thisWeek = JsonPath.read(overview, "$.cohorts[7].retained");
        assertThat(thisWeek).hasSize(1);
    }

    // ---------------------------------------------------------------- helpers

    private String overview() throws Exception {
        return mvc.perform(get("/api/core/admin/overview").with(OWNER)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static long delta(String before, String after, String path) {
        return ((Number) JsonPath.read(after, path)).longValue() - ((Number) JsonPath.read(before, path)).longValue();
    }

    private static RequestPostProcessor newUser() {
        return user(UUID.randomUUID() + "@example.com", UUID.randomUUID().toString());
    }

    private static RequestPostProcessor user(String email, String subject) {
        return jwt().jwt(j -> j.subject(subject).claim("email", email).claim("email_verified", true));
    }
}
