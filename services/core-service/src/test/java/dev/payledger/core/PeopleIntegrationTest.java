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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** People across groups, and loans: lending is money owed back, borrowing is money in, neither is spending. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PeopleIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void whatYouAndEachPersonOweAddsUpAcrossGroupsAndLoans() throws Exception {
        String raviEmail = UUID.randomUUID() + "@example.com";
        var asha = user(UUID.randomUUID() + "@example.com");
        var ravi = user(raviEmail);
        String raviSpace = JsonPath.read(body(get("/api/core/me"), ravi, null), "$.spaces[0].id");
        String ashaSpace = JsonPath.read(body(get("/api/core/me"), asha, null), "$.spaces[0].id");
        String raviBank = JsonPath.read(body(post("/api/core/spaces/" + raviSpace + "/accounts"), ravi,
                "{\"kind\":\"BANK\",\"name\":\"SBI\",\"openingBalance\":0}"), "$.id");

        // A trip: Asha pays ₹600 for both, so Ravi owes her ₹300.
        String group = JsonPath.read(body(post("/api/core/groups"), asha, "{\"name\":\"Trip\"}"), "$.id");
        String members = body(post("/api/core/groups/" + group + "/members"), asha, "{\"name\":\"Ravi\",\"email\":\"" + raviEmail + "\"}");
        String a = JsonPath.<List<String>>read(members, "$.members[?(@.you == true)].id").getFirst();
        String r = JsonPath.<List<String>>read(members, "$.members[?(@.name == 'Ravi')].id").getFirst();
        body(post("/api/core/groups/" + group + "/expenses"), asha, """
                {"description":"Taxi","amount":60000,"occurredOn":"2026-10-01","paidBy":[{"memberId":"%s","value":60000}],
                 "split":"EQUAL","shares":[{"memberId":"%s"},{"memberId":"%s"}]}""".formatted(a, a, r));

        // Ravi borrows ₹2,000 from Asha, into his SBI, due on the 20th: a new one-to-one ledger.
        String key = JsonPath.<List<String>>read(body(get("/api/core/people"), ravi, null), "$.people[*].key").getFirst();
        body(post("/api/core/people/loans"), ravi, """
                {"personKey":"%s","direction":"BORROWED","amount":200000,"occurredOn":"2026-10-02","dueOn":"2026-10-20","accountId":"%s"}
                """.formatted(key, raviBank));

        String people = body(get("/api/core/people"), asha, null);
        assertThat(JsonPath.<Integer>read(people, "$.people.length()")).isEqualTo(1);
        assertThat(JsonPath.<Number>read(people, "$.people[0].net").longValue()).isEqualTo(230_000);
        assertThat(JsonPath.<Number>read(people, "$.totals.owedToYou").longValue()).isEqualTo(230_000);
        assertThat(JsonPath.<String>read(people, "$.people[0].nextDue")).isEqualTo("2026-10-20");
        assertThat(JsonPath.<List<Object>>read(people, "$.people[0].groups")).hasSize(2);
        mvc.perform(get("/api/core/people").with(ravi)).andExpect(jsonPath("$.totals.youOwe").value(230_000));

        // Your groups list leaves the one-to-one ledger out.
        mvc.perform(get("/api/core/groups").with(asha)).andExpect(jsonPath("$.length()").value(1));

        // In Ravi's book the loan is money in, owed back: no spending.
        List<Map<String, Object>> book = jdbc.sql("""
                        select t.kind, t.amount, t.category_id from transactions t
                        where t.space_id = ?::uuid and t.deleted_at is null and t.description like 'Borrowed%'
                        """).param(raviSpace).query().listOfRows();
        assertThat(book).singleElement().satisfies(row -> {
            assertThat(row.get("kind")).isEqualTo("TRANSFER");
            assertThat(row.get("category_id")).isNull();
        });
        // Asha had no account to lend from in this test, so only Ravi's side is booked.
        assertThat(jdbc.sql("select count(*) from transactions where space_id = ?::uuid and description like 'Lent%'").param(ashaSpace)
                .query(Long.class).single()).isZero();

        // A person outside your groups can't be named by key.
        mvc.perform(post("/api/core/people/loans").with(user(UUID.randomUUID() + "@example.com")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personKey\":\"%s\",\"direction\":\"LENT\",\"amount\":100,\"occurredOn\":\"2026-10-02\"}".formatted(key)))
                .andExpect(status().isNotFound());
    }

    private String body(MockHttpServletRequestBuilder request, RequestPostProcessor who, String json) throws Exception {
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request.with(who)).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private static RequestPostProcessor user(String email) {
        String subject = UUID.randomUUID().toString();
        return jwt().jwt(j -> j.subject(subject).claim("email", email).claim("email_verified", true));
    }
}
