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

import java.lang.reflect.Method;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A month looked back on: totals against the month before, what moved, and the notice on the 1st. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MonthReviewIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired dev.payledger.core.budget.MonthReview review;

    @Test
    void aMonthComparedWithTheOneBefore() throws Exception {
        String subject = UUID.randomUUID().toString();
        RequestPostProcessor me = jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
        String space = JsonPath.read(mvc.perform(get("/api/core/me").with(me)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
        String base = "/api/core/spaces/" + space;
        String bank = JsonPath.read(ok(post(base + "/accounts"), me, "{\"kind\":\"BANK\",\"name\":\"Bank\"}"), "$.id");
        String cats = mvc.perform(get(base + "/categories").with(me)).andReturn().getResponse().getContentAsString();
        String food = JsonPath.<List<String>>read(cats, "$[?(@.name == 'Groceries')].id").getFirst();
        String salary = JsonPath.<List<String>>read(cats, "$[?(@.kind == 'INCOME')].id").getFirst();
        // August: ₹10,000 on groceries. September: ₹1,00,000 in, ₹15,000 on groceries in one go.
        txn(me, base, "EXPENSE", 1_000_000, bank, food, "2026-08-10");
        txn(me, base, "INCOME", 10_000_000, bank, salary, "2026-09-01");
        txn(me, base, "EXPENSE", 1_500_000, bank, food, "2026-09-12");

        String r = mvc.perform(get(base + "/insights/review").param("month", "2026-09").with(me)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(r, "$.income")).isEqualTo(10_000_000);
        assertThat(JsonPath.<Integer>read(r, "$.spent")).isEqualTo(1_500_000);
        assertThat(JsonPath.<Integer>read(r, "$.spentBefore")).isEqualTo(1_000_000);
        assertThat(JsonPath.<Integer>read(r, "$.noSpendDays")).isEqualTo(29);
        assertThat(JsonPath.<List<String>>read(r, "$.highlights"))
                .contains("You kept ₹85,000, 85% of what came in.", "Spending was 50% higher than the month before.",
                        "The biggest rise was Groceries: up ₹5,000.", "29 days without spending anything.");

        // The notice on the 1st, once.
        Method announce = review.getClass().getDeclaredMethod("announce", YearMonth.class);
        announce.setAccessible(true);
        announce.invoke(review, YearMonth.of(2026, 9));
        announce.invoke(review, YearMonth.of(2026, 9));
        UUID user = jdbc.sql("select id from users where subject = :s").param("s", subject).query(UUID.class).single();
        assertThat(jdbc.sql("select count(*) from inbox where user_id = :u and kind = 'REVIEW'").param("u", user).query(Long.class).single()).isEqualTo(1);
        mvc.perform(get(base + "/insights/review").with(mvcStranger())).andExpect(status().isNotFound());
    }

    private void txn(RequestPostProcessor me, String base, String kind, long amount, String account, String category, String on) throws Exception {
        ok(post(base + "/transactions"), me, """
                {"kind":"%s","amount":%d,"accountId":"%s","categoryId":"%s","occurredOn":"%s","description":"x"}
                """.formatted(kind, amount, account, category, on));
    }

    private static RequestPostProcessor mvcStranger() {
        String subject = UUID.randomUUID().toString();
        return jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
    }

    private String ok(MockHttpServletRequestBuilder request, RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(request.with(who).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }
}
