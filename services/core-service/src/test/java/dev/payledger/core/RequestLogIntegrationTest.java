package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every request on record, with its id, route, outcome and time; errors with their code and message; logs exportable. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "payledger.admin.owners=logkeeper@payledger.example")
@AutoConfigureMockMvc
class RequestLogIntegrationTest {

    static final RequestPostProcessor OWNER = user("logkeeper@payledger.example", UUID.randomUUID().toString());

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void everyRequestIsLoggedWithItsIdRouteStatusAndTime() throws Exception {
        String email = unique() + "@example.com";
        var me = user(email, UUID.randomUUID().toString());
        MockHttpServletResponse r = mvc.perform(get("/api/core/me").with(me)).andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id")).andReturn().getResponse();
        Map<String, Object> row = row(r.getHeader("X-Request-Id"));
        assertThat(row).containsEntry("method", "GET").containsEntry("path", "/api/core/me").containsEntry("route", "/api/core/me");
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(200);
        assertThat(row.get("user_id")).isNotNull();
        assertThat(((Number) row.get("duration_ms")).intValue()).isGreaterThanOrEqualTo(0);
        // The first visit is a sign-up, on record.
        assertThat(jdbc.sql("select count(*) from user_events e join users u on u.id = e.user_id where u.email = ? and e.type = 'SIGN_UP'")
                .param(email).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void errorsCarryTheRequestIdAndAreLoggedWithTheirCodeAndMessage() throws Exception {
        var me = newUser();
        String space = JsonPath.read(mvc.perform(get("/api/core/me").with(me)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
        MockHttpServletResponse missing = mvc.perform(get("/api/core/spaces/" + UUID.randomUUID() + "/accounts").with(me))
                .andExpect(status().isNotFound()).andReturn().getResponse();
        String id = missing.getHeader("X-Request-Id");
        assertThat((String) JsonPath.read(missing.getContentAsString(), "$.requestId")).isEqualTo(id);
        assertThat(row(id)).containsEntry("error_code", "space-not-found").containsEntry("route", "/api/core/spaces/{spaceId}/accounts");
        assertThat((String) row(id).get("error_detail")).contains("not a member");

        // Spring's own errors (bad input) too.
        MockHttpServletResponse bad = mvc.perform(post("/api/core/spaces/" + space + "/accounts").with(me)
                .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"BANK\"}")).andExpect(status().isBadRequest()).andReturn().getResponse();
        assertThat(row(bad.getHeader("X-Request-Id"))).containsEntry("error_code", "http-400");
        assertThat((String) JsonPath.read(bad.getContentAsString(), "$.requestId")).isEqualTo(bad.getHeader("X-Request-Id"));

        // Refused before anything runs (no token): still on record, without a user.
        MockHttpServletResponse anonymous = mvc.perform(get("/api/core/me")).andExpect(status().isUnauthorized()).andReturn().getResponse();
        Map<String, Object> refused = row(anonymous.getHeader("X-Request-Id"));
        assertThat(((Number) refused.get("status")).intValue()).isEqualTo(401);
        assertThat(refused).containsEntry("error_code", "unauthorized");
        assertThat(refused.get("user_id")).isNull();
    }

    @Test
    void staffFindRequestsByIdOrErrorAndExportEveryLogAsCsv() throws Exception {
        var me = newUser();
        String id = mvc.perform(get("/api/core/spaces/" + UUID.randomUUID() + "/accounts").with(me)).andReturn().getResponse().getHeader("X-Request-Id");
        mvc.perform(get("/api/core/admin/requests").param("q", id).with(OWNER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.rows[0].error_code").value("space-not-found"));
        mvc.perform(get("/api/core/admin/requests").param("status", "errors").param("limit", "5").with(OWNER))
                .andExpect(jsonPath("$.rows[0].status").value(org.hamcrest.Matchers.greaterThanOrEqualTo(400)));

        MockHttpServletResponse csv = mvc.perform(get("/api/core/admin/export/requests.csv").param("q", id).with(OWNER))
                .andExpect(status().isOk()).andReturn().getResponse();
        String text = new String(csv.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv.getHeader("Content-Disposition")).startsWith("attachment; filename=\"payledger-requests-");
        assertThat(text).startsWith("﻿id,at,request_id,method,path,route,status").contains(id).contains("space-not-found")
                // Times to the millisecond, in IST.
                .containsPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+05:30");
        for (String kind : new String[]{"activity", "audit", "emails", "users"}) {
            mvc.perform(get("/api/core/admin/export/" + kind + ".csv").with(OWNER)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/core/admin/audit").with(OWNER)).andExpect(jsonPath("$[0].action").value("export.users"));
        mvc.perform(get("/api/core/admin/export/requests.csv").with(me)).andExpect(status().isForbidden());
        mvc.perform(get("/api/core/admin/overview").with(OWNER)).andExpect(jsonPath("$.platform.requests24h").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void aCellThatLooksLikeAFormulaIsDefused() throws Exception {
        var me = user("=cmd" + unique() + "@example.com", UUID.randomUUID().toString());
        mvc.perform(get("/api/core/me").with(me));
        String text = mvc.perform(get("/api/core/admin/export/users.csv").param("q", "=cmd").with(OWNER)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(text).contains("\"'=cmd").doesNotContain(",=cmd");
    }

    private Map<String, Object> row(String requestId) {
        return jdbc.sql("select * from request_log where request_id = ?").param(requestId).query().singleRow();
    }

    private static String unique() {
        return "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static RequestPostProcessor newUser() {
        return user(unique() + "@example.com", UUID.randomUUID().toString());
    }

    private static RequestPostProcessor user(String email, String subject) {
        return jwt().jwt(j -> j.subject(subject).claim("email", email).claim("email_verified", true));
    }
}
