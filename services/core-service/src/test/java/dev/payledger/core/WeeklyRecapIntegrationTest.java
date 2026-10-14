package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
import dev.payledger.core.notify.WeeklyRecap;
import dev.payledger.core.user.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Monday's recap: last week against the week before, where it went, what's due; once a week, and only with news. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class WeeklyRecapIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired WeeklyRecap recap;
    @Autowired TransactionTemplate tx;

    @Test
    void lastWeekIsComparedWithTheWeekBeforeAndSentOnce() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        String subject = UUID.randomUUID().toString();
        RequestPostProcessor me = jwt().jwt(j -> j.subject(subject).claim("email", email).claim("email_verified", true));
        String space = JsonPath.read(mvc.perform(get("/api/core/me").with(me)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
        String categories = mvc.perform(get("/api/core/spaces/" + space + "/categories").with(me)).andReturn().getResponse().getContentAsString();
        String food = JsonPath.<List<String>>read(categories, "$[?(@.name == 'Groceries')].id").getFirst();
        String travel = JsonPath.<List<String>>read(categories, "$[?(@.name == 'Travel')].id").getFirst();
        String bank = JsonPath.read(mvc.perform(post("/api/core/spaces/" + space + "/accounts").with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"BANK\",\"name\":\"HDFC\",\"openingBalance\":0,\"openingDate\":\"2026-01-01\"}")).andReturn().getResponse().getContentAsString(), "$.id");
        // Monday 19 Oct 2026: last week is 12–18 Oct, the week before 5–11 Oct.
        LocalDate monday = LocalDate.of(2026, 10, 19);
        for (String[] t : new String[][] {{"2026-10-13", "40000", food}, {"2026-10-17", "20000", travel}, {"2026-10-08", "40000", food}}) {
            mvc.perform(post("/api/core/spaces/" + space + "/transactions").with(me).contentType(MediaType.APPLICATION_JSON).content(
                    "{\"kind\":\"EXPENSE\",\"amount\":" + t[1] + ",\"accountId\":\"" + bank + "\",\"categoryId\":\"" + t[2] + "\",\"occurredOn\":\"" + t[0] + "\",\"description\":\"x\"}"));
        }
        UUID userId = jdbc.sql("select id from users where subject = ?").param(subject).query(UUID.class).single();
        CurrentUser user = new CurrentUser(userId, subject, email, null, UUID.fromString(space));

        assertThat(Boolean.TRUE.equals(tx.execute(s -> (Boolean) recap.recap(user, monday)))).isTrue();
        record Item(String title, String body, String link) {
        }
        Item item = jdbc.sql("select title, body, link from inbox where user_id = ? and kind = 'RECAP'").param(userId).query(Item.class).single();
        assertThat(item.title()).isEqualTo("Your week: ₹600 spent");
        assertThat(item.body()).isEqualTo("12 Oct – 18 Oct, ₹200 more than the week before.");
        assertThat(item.link()).isEqualTo("/reports?period=custom&from=2026-10-12&to=2026-10-18");
        String html = jdbc.sql("select body_html from email_outbox where user_id = ? and kind = 'WEEKLY_RECAP'").param(userId).query(String.class).single();
        assertThat(html).contains("Groceries: ₹400").contains("Travel: ₹200");

        // Once a week, whatever the time it runs again; and nothing for a week with nothing in it.
        assertThat(Boolean.TRUE.equals(tx.execute(s -> (Boolean) recap.recap(user, monday.plusDays(2))))).isFalse();
        assertThat(Boolean.TRUE.equals(tx.execute(s -> (Boolean) recap.recap(user, monday.plusWeeks(3))))).isFalse();
    }
}
