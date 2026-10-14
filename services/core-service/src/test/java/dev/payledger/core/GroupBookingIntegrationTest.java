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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Your share of group expenses in your own book: only your share is spending, what you paid for others sits in
 * "Friends owe me", and settling up moves money between it and your bank.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GroupBookingIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    record Row(String kind, long amount, String account, String toAccount, String category, String groupLink) {
    }

    @Test
    void yourShareIsSpendingWhatOthersOweYouIsNotAndSettlingUpEvensItOut() throws Exception {
        String raviEmail = UUID.randomUUID() + "@example.com";
        var asha = user(UUID.randomUUID() + "@example.com");
        var ravi = user(raviEmail);
        String ashaSpace = JsonPath.read(body(get("/api/core/me"), asha, null), "$.spaces[0].id");
        String raviSpace = JsonPath.read(body(get("/api/core/me"), ravi, null), "$.spaces[0].id");
        String bank = JsonPath.read(body(post("/api/core/spaces/" + ashaSpace + "/accounts"), asha,
                "{\"kind\":\"BANK\",\"name\":\"HDFC\",\"openingBalance\":0,\"openingDate\":\"2026-01-01\"}"), "$.id");

        String group = JsonPath.read(body(post("/api/core/groups"), asha, "{\"name\":\"Goa\"}"), "$.id");
        String members = body(post("/api/core/groups/" + group + "/members"), asha, "{\"name\":\"Ravi\",\"email\":\"" + raviEmail + "\"}");
        String a = JsonPath.<List<String>>read(members, "$.members[?(@.you == true)].id").getFirst();
        String r = JsonPath.<List<String>>read(members, "$.members[?(@.name == 'Ravi')].id").getFirst();

        // Asha pays ₹900 for dinner for both, from HDFC; the group calls it "Eating out".
        String expense = JsonPath.read(body(post("/api/core/groups/" + group + "/expenses"), asha, """
                {"description":"Dinner","amount":90000,"occurredOn":"2026-10-05","category":"eating out","paidBy":[{"memberId":"%s","value":90000}],
                 "split":"EQUAL","shares":[{"memberId":"%s"},{"memberId":"%s"}],"paidFromAccountId":"%s"}
                """.formatted(a, a, r, bank)), "$.id");
        assertThat(book(ashaSpace)).containsExactlyInAnyOrder(
                new Row("EXPENSE", 45_000, "Friends owe me", null, "Eating out", "gexp:" + expense + ":share"),
                new Row("TRANSFER", 90_000, "HDFC", "Friends owe me", null, "gexp:" + expense + ":paid"));
        // Ravi's share is his spending too, owed to the group: no bank account needed.
        assertThat(book(raviSpace)).containsExactly(new Row("EXPENSE", 45_000, "Friends owe me", null, "Eating out", "gexp:" + expense + ":share"));

        // An edit rebooks it.
        String expenseJson = body(get("/api/core/groups/" + group + "/activity"), asha, null);
        int version = JsonPath.read(expenseJson, "$.expenses[0].version");
        body(put("/api/core/groups/" + group + "/expenses/" + expense).param("version", String.valueOf(version)), asha, """
                {"description":"Dinner","amount":100000,"occurredOn":"2026-10-05","category":"eating out","paidBy":[{"memberId":"%s","value":100000}],
                 "split":"EQUAL","shares":[{"memberId":"%s"},{"memberId":"%s"}]}
                """.formatted(a, a, r));
        assertThat(book(ashaSpace)).extracting(Row::amount).containsExactlyInAnyOrder(50_000L, 100_000L);

        // Ravi pays Asha back ₹500: it lands in Asha's HDFC from "Friends owe me".
        body(post("/api/core/groups/" + group + "/settlements"), ravi,
                "{\"fromMemberId\":\"" + r + "\",\"toMemberId\":\"" + a + "\",\"amount\":50000,\"occurredOn\":\"2026-10-06\"}");
        assertThat(book(ashaSpace)).anyMatch(x -> x.kind().equals("TRANSFER") && x.amount() == 50_000 && x.account().equals("Friends owe me") && "HDFC".equals(x.toAccount()));

        // Deleting the expense unbooks the shares.
        body(delete("/api/core/groups/" + group + "/expenses/" + expense).param("version", String.valueOf(version + 1)), asha, null);
        assertThat(book(raviSpace)).isEmpty();
        assertThat(book(ashaSpace)).extracting(Row::groupLink).allMatch(l -> l.startsWith("gset:"));

        // Turning it off takes your bookings out; the account isn't one you can make or count against Free.
        mvc.perform(put("/api/core/me/group-budget").with(asha).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        assertThat(book(ashaSpace)).isEmpty();
        mvc.perform(post("/api/core/spaces/" + ashaSpace + "/accounts").with(asha).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"OWED\",\"name\":\"Mine\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-kind"));
    }

    private List<Row> book(String space) {
        return jdbc.sql("""
                        select t.kind, t.amount, a.name as account, ta.name as to_account, c.name as category, t.group_link
                        from transactions t join money_accounts a on a.id = t.account_id left join money_accounts ta on ta.id = t.to_account_id
                        left join categories c on c.id = t.category_id
                        where t.space_id = ?::uuid and t.deleted_at is null and t.group_link is not null
                        """).param(space).query(Row.class).list();
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
