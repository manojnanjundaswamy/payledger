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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Milk by the day and a cook by the month: missed and extra days, paid leaves, paying once, undoing. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DailyServicesIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void theMonthsBillFollowsTheMarkedDaysAndIsPaidOnce() throws Exception {
        String subject = UUID.randomUUID().toString();
        RequestPostProcessor me = jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
        String space = JsonPath.read(mvc.perform(get("/api/core/me").with(me)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
        String base = "/api/core/spaces/" + space + "/daily-services";
        String bank = JsonPath.read(ok(post("/api/core/spaces/" + space + "/accounts"), me, "{\"kind\":\"BANK\",\"name\":\"HDFC\"}"), "$.id");

        // Milk: ₹60 a day in September (30 days); the 3rd missed, the 4th double.
        String milk = JsonPath.read(ok(post(base), me, "{\"name\":\"Milk\",\"rate\":6000,\"per\":\"DAY\",\"accountId\":\"" + bank + "\",\"startedOn\":\"2026-09-01\"}"), "$.service.id");
        ok(put(base + "/" + milk + "/days/2026-09-03"), me, "{\"quantity\":0}");
        String sept = ok(put(base + "/" + milk + "/days/2026-09-04"), me, "{\"quantity\":2}");
        assertThat(JsonPath.<Integer>read(sept, "$.bill.amount")).isEqualTo(180_000);
        assertThat(JsonPath.<Integer>read(sept, "$.bill.missed")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(sept, "$.bill.extra")).isEqualTo(1);
        // Back to normal removes the mark.
        String normal = ok(put(base + "/" + milk + "/days/2026-09-04"), me, "{\"quantity\":1}");
        assertThat(JsonPath.<Integer>read(normal, "$.bill.amount")).isEqualTo(174_000);
        mvc.perform(put(base + "/" + milk + "/days/2026-08-31").with(me).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":0}"))
                .andExpect(jsonPath("$.code").value("outside-service"));

        // A cook at ₹12,000 a month with 2 paid leaves: 4 days off in September costs 2 days' pay.
        String cook = JsonPath.read(ok(post(base), me, "{\"name\":\"Cook\",\"rate\":1200000,\"per\":\"MONTH\",\"paidLeaves\":2,\"startedOn\":\"2026-09-01\"}"), "$.service.id");
        String last = null;
        for (String day : new String[]{"2026-09-07", "2026-09-14", "2026-09-21", "2026-09-28"}) {
            last = ok(put(base + "/" + cook + "/days/" + day), me, "{\"quantity\":0}");
        }
        assertThat(JsonPath.<Integer>read(last, "$.bill.amount")).isEqualTo(1_120_000);
        mvc.perform(put(base + "/" + cook + "/days/2026-09-08").with(me).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2}"))
                .andExpect(jsonPath("$.code").value("invalid-quantity"));
        // The cook has no account set: paying needs one.
        mvc.perform(post(base + "/" + cook + "/payments").with(me).contentType(MediaType.APPLICATION_JSON).content("{\"month\":\"2026-09\"}"))
                .andExpect(jsonPath("$.code").value("account-required"));

        // Paying the milk records one expense; the month is then fixed.
        String paid = ok(post(base + "/" + milk + "/payments"), me, "{\"month\":\"2026-09\"}");
        String txn = JsonPath.read(paid, "$.bill.paid.transactionId");
        record Row(long amount, String description, String kind) {
        }
        Row t = jdbc.sql("select amount, description, kind from transactions where id = :id").param("id", UUID.fromString(txn)).query(Row.class).single();
        assertThat(t.amount()).isEqualTo(174_000);
        assertThat(t.kind()).isEqualTo("EXPENSE");
        assertThat(t.description()).isEqualTo("Milk: September 2026 (29 deliveries, 1 missed)");
        mvc.perform(post(base + "/" + milk + "/payments").with(me).contentType(MediaType.APPLICATION_JSON).content("{\"month\":\"2026-09\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already-paid"));
        mvc.perform(put(base + "/" + milk + "/days/2026-09-10").with(me).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("month-paid"));

        // Undo: the expense goes, the month opens again.
        mvc.perform(delete(base + "/" + milk + "/payments/2026-09").with(me)).andExpect(status().isOk()).andExpect(jsonPath("$.bill.paid").doesNotExist());
        assertThat(jdbc.sql("select deleted_at is not null from transactions where id = :id").param("id", UUID.fromString(txn)).query(Boolean.class).single()).isTrue();

        // A paid service, removed, stops instead of vanishing; an unpaid one goes.
        ok(post(base + "/" + milk + "/payments"), me, "{\"month\":\"2026-09\"}");
        mvc.perform(delete(base + "/" + milk).with(me)).andExpect(status().isNoContent());
        mvc.perform(delete(base + "/" + cook).with(me)).andExpect(status().isNoContent());
        mvc.perform(get(base).param("month", "2026-09").with(me))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].service.endedOn").exists());

        // Not someone else's.
        var stranger = jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("email", UUID.randomUUID() + "@example.com").claim("email_verified", true));
        mvc.perform(get(base).with(stranger)).andExpect(status().isNotFound());
    }

    private String ok(MockHttpServletRequestBuilder request, RequestPostProcessor who, String body) throws Exception {
        ResultActions r = mvc.perform(request.with(who).contentType(MediaType.APPLICATION_JSON).content(body));
        return r.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }
}
