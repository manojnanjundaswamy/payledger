package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
import dev.payledger.core.money.AutoPay;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Automatic bills: recorded once on the due date, matched to your own entry instead when you made one, never backdated. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AutoPayIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired AutoPay autoPay;

    @Test
    void anAutomaticBillRecordsItselfOnceAndUsesYourEntryWhenYouMadeOne() throws Exception {
        String subject = UUID.randomUUID().toString();
        RequestPostProcessor me = jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
        String space = JsonPath.read(body(get("/api/core/me"), me, null), "$.spaces[0].id");
        String base = "/api/core/spaces/" + space;
        String bank = JsonPath.read(body(post(base + "/accounts"), me, "{\"kind\":\"BANK\",\"name\":\"HDFC\",\"openingBalance\":10000000}"), "$.id");
        String category = JsonPath.<List<String>>read(body(get(base + "/categories"), me, null), "$[?(@.kind == 'EXPENSE')].id").getFirst();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        String emi = bill(me, base, "Car EMI", 1_500_000, bank, category, today, true);
        // Turned on today, due a month ago too: only today's is recorded.
        String sip = bill(me, base, "Gym", 200_000, bank, category, today.minusMonths(1), true);
        String manual = bill(me, base, "Rent", 2_000_000, bank, category, today, false);
        // You entered the gym yourself: that entry is linked, not a second one.
        body(post(base + "/transactions"), me, """
                {"kind":"EXPENSE","amount":200000,"accountId":"%s","categoryId":"%s","occurredOn":"%s","description":"Cult gym"}
                """.formatted(bank, category, today));

        autoPay.runAll();
        autoPay.runAll();

        assertThat(count(space, "Car EMI")).isEqualTo(1);
        assertThat(count(space, "Gym")).isZero();
        assertThat(count(space, "Cult gym")).isEqualTo(1);
        assertThat(count(space, "Rent")).isZero();
        assertThat(jdbc.sql("select count(*) from commitment_payments where commitment_id in (?::uuid, ?::uuid)").params(emi, sip)
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from commitment_payments where commitment_id = ?::uuid").param(manual).query(Long.class).single()).isZero();
        mvc.perform(get(base + "/commitments").with(me)).andExpect(jsonPath("$[?(@.name == 'Car EMI')].auto").value(true));

        // Turned off and on again: from that day, so nothing earlier comes back.
        int version = JsonPath.<List<Integer>>read(body(get(base + "/commitments"), me, null), "$[?(@.name == 'Rent')].version").getFirst();
        body(put(base + "/commitments/" + manual).param("version", String.valueOf(version)), me, fields("Rent", 2_000_000, bank, category, today, true));
        autoPay.runAll();
        assertThat(count(space, "Rent")).isEqualTo(1);
    }

    private String bill(RequestPostProcessor me, String base, String name, long amount, String bank, String category, LocalDate first, boolean auto) throws Exception {
        return JsonPath.read(body(post(base + "/commitments"), me, fields(name, amount, bank, category, first, auto)), "$.id");
    }

    private static String fields(String name, long amount, String bank, String category, LocalDate first, boolean auto) {
        return """
                {"kind":"BILL","name":"%s","amount":%d,"accountId":"%s","categoryId":"%s","everyMonths":1,"firstDue":"%s","auto":%s}
                """.formatted(name, amount, bank, category, first, auto);
    }

    private long count(String space, String description) {
        return jdbc.sql("select count(*) from transactions where space_id = ?::uuid and description = ? and deleted_at is null")
                .params(UUID.fromString(space), description).query(Long.class).single();
    }

    private String body(MockHttpServletRequestBuilder request, RequestPostProcessor who, String json) throws Exception {
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request.with(who)).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }
}
