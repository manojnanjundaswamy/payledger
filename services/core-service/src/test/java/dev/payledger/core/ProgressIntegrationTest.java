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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Streaks, badges once each (and announced once), points and levels. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ProgressIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void goodHabitsEarnBadgesOnceAndAStreakGrowsDayByDay() throws Exception {
        String subject = UUID.randomUUID().toString();
        RequestPostProcessor me = jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
        String space = JsonPath.read(mvc.perform(get("/api/core/me").with(me)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
        mvc.perform(get("/api/core/me/progress").with(me)).andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("Seedling")).andExpect(jsonPath("$.streak.current").value(0))
                .andExpect(jsonPath("$.badges.length()").value(15)).andExpect(jsonPath("$.challenge.title").exists());

        String base = "/api/core/spaces/" + space;
        String bank = JsonPath.read(ok(post(base + "/accounts"), me, "{\"kind\":\"BANK\",\"name\":\"Bank\"}"), "$.id");
        String food = JsonPath.<List<String>>read(mvc.perform(get(base + "/categories").with(me)).andReturn().getResponse().getContentAsString(),
                "$[?(@.name == 'Groceries')].id").getFirst();
        for (int i = 0; i < 7; i++) {
            ok(post(base + "/transactions"), me, """
                    {"kind":"EXPENSE","amount":10000,"accountId":"%s","categoryId":"%s","occurredOn":"2026-10-01","description":"Milk %d"}
                    """.formatted(bank, food, i));
        }
        // Spread over the last seven days, as if recorded once a day.
        List<UUID> ids = jdbc.sql("select id from transactions where space_id = :s order by created_at").param("s", UUID.fromString(space)).query(UUID.class).list();
        for (int i = 0; i < ids.size(); i++) {
            jdbc.sql("update transactions set created_at = now() - make_interval(days => :d) where id = :id").param("d", 6 - i).param("id", ids.get(i)).update();
        }

        String first = mvc.perform(get("/api/core/me/progress").with(me)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(first, "$.streak.current")).isEqualTo(7);
        assertThat(JsonPath.<Boolean>read(first, "$.streak.today")).isTrue();
        assertThat(JsonPath.<List<String>>read(first, "$.fresh")).contains("first-entry", "streak-7");
        assertThat(JsonPath.<Integer>read(first, "$.points")).isGreaterThanOrEqualTo(20 + 60 + 14);
        UUID user = jdbc.sql("select id from users where subject = :s").param("s", subject).query(UUID.class).single();
        // Each badge earned is announced once (no-spend-day too: nothing spent in the days after the first week).
        long fresh = JsonPath.<List<String>>read(first, "$.fresh").size();
        assertThat(jdbc.sql("select count(*) from inbox where user_id = :u and kind = 'ACHIEVEMENT' and title like 'Badge earned:%'")
                .param("u", user).query(Long.class).single()).isEqualTo(fresh);

        // Again: nothing new, nothing announced twice.
        mvc.perform(get("/api/core/me/progress").with(me)).andExpect(jsonPath("$.fresh.length()").value(0));
        assertThat(jdbc.sql("select count(*) from inbox where user_id = :u and kind = 'ACHIEVEMENT' and title like 'Badge earned:%'")
                .param("u", user).query(Long.class).single()).isEqualTo(fresh);

        // A gap breaks the current streak; the best one stays.
        jdbc.sql("update transactions set created_at = now() - interval '10 days' where space_id = :s").param("s", UUID.fromString(space)).update();
        mvc.perform(get("/api/core/me/progress").with(me))
                .andExpect(jsonPath("$.streak.current").value(0)).andExpect(jsonPath("$.streak.best").value(1));
        assertThat(LocalDate.now(ZoneId.of("Asia/Kolkata"))).isNotNull();
    }

    private String ok(MockHttpServletRequestBuilder request, RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(request.with(who).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }
}
