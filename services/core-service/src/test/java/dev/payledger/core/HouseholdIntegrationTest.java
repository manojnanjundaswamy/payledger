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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A household: members and roles, invites by email, shared money, personal money private, handed over on leaving. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "payledger.plans.complimentary=asha.household@example.com")
@AutoConfigureMockMvc
class HouseholdIntegrationTest {

    @org.springframework.test.context.DynamicPropertySource
    static void keycloak(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("payledger.identity.url", FakeKeycloak::url);
        registry.add("payledger.identity.client-secret", () -> FakeKeycloak.SECRET);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void aFamilySharesAHouseholdWhileTheirOwnMoneyStaysTheirOwn() throws Exception {
        var asha = user("asha.household@example.com");
        String raviEmail = "ravi-" + UUID.randomUUID() + "@example.com";
        var ravi = user(raviEmail);
        String raviPersonal = JsonPath.read(body(get("/api/core/me"), ravi, null), "$.spaces[0].id");
        String raviId = JsonPath.read(body(get("/api/core/me"), ravi, null), "$.id");

        // Free can't start one; Premium (Asha) can.
        mvc.perform(post("/api/core/households").with(ravi).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Ravi's\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan-limit"));
        String home = JsonPath.read(body(post("/api/core/households"), asha, "{\"name\":\"Sharma home\"}"), "$.id");

        // Ravi has an account: added at once, and the household shows in his spaces.
        body(post("/api/core/households/" + home + "/invites"), asha, "{\"email\":\"" + raviEmail.toUpperCase() + "\",\"role\":\"PARTNER\"}");
        assertThat(JsonPath.<List<String>>read(body(get("/api/core/me"), ravi, null), "$.spaces[*].id")).contains(home);

        // Meera hasn't signed up: an email with a link, which works only for her address.
        String meeraEmail = "meera-" + UUID.randomUUID() + "@example.com";
        body(post("/api/core/households/" + home + "/invites"), asha, "{\"email\":\"" + meeraEmail + "\",\"role\":\"VIEWER\"}");
        String mail = jdbc.sql("select body_html from email_outbox where lower(to_email) = ? order by created_at desc limit 1").param(meeraEmail)
                .query(String.class).single();
        Matcher m = Pattern.compile("/household/join\\?token=([A-Za-z0-9_-]+)").matcher(mail);
        assertThat(m.find()).isTrue();
        String token = m.group(1);
        var stranger = user("someone-" + UUID.randomUUID() + "@example.com");
        mvc.perform(get("/api/core/households/invite").param("token", token).with(stranger)).andExpect(jsonPath("$.forYou").value(false))
                .andExpect(jsonPath("$.household").value("Sharma home"));
        mvc.perform(post("/api/core/households/join").with(stranger).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invite-other-email"));
        var meera = user(meeraEmail);
        body(post("/api/core/households/join"), meera, "{\"token\":\"" + token + "\"}");
        mvc.perform(post("/api/core/households/join").with(meera).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
                .andExpect(jsonPath("$.code").value("invite-invalid"));

        // Shared money: both record spending in the joint account; the viewer can only look.
        String joint = JsonPath.read(body(post("/api/core/spaces/" + home + "/accounts"), asha, "{\"kind\":\"BANK\",\"name\":\"Joint\",\"openingBalance\":0}"), "$.id");
        String groceries = JsonPath.<List<String>>read(body(get("/api/core/spaces/" + home + "/categories"), ravi, null), "$[?(@.name == 'Groceries')].id").getFirst();
        expense(asha, home, joint, groceries, 300_000);
        expense(ravi, home, joint, groceries, 100_000);
        mvc.perform(post("/api/core/spaces/" + home + "/transactions").with(meera).contentType(MediaType.APPLICATION_JSON)
                        .content(txn(joint, groceries, 5_000))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("space-read-only"));
        String view = body(get("/api/core/households/" + home), meera, null);
        assertThat(JsonPath.<Integer>read(view, "$.spent")).isEqualTo(400_000);
        assertThat(JsonPath.<List<Double>>read(view, "$.contributions[?(@.name == 'asha.household')].share")).containsExactly(0.75);
        assertThat(JsonPath.<List<Object>>read(view, "$.invites")).isEmpty(); // a viewer doesn't see invites

        // Personal money stays private: Asha can't open Ravi's personal space.
        mvc.perform(get("/api/core/spaces/" + raviPersonal + "/accounts").with(asha)).andExpect(status().isNotFound());

        // Roles: only the owner changes them; the owner can't just leave.
        mvc.perform(patch("/api/core/households/" + home + "/members/" + raviId).with(ravi).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isForbidden());
        String ashaId = JsonPath.read(body(get("/api/core/me"), asha, null), "$.id");
        mvc.perform(delete("/api/core/households/" + home + "/members/" + ashaId).with(asha))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("owner-stays"));

        // Asha deletes her account: the household goes to Ravi (the partner) with everything in it.
        body(post("/api/core/me/delete"), asha, "{\"confirm\":\"asha.household@example.com\"}");
        assertThat(jdbc.sql("select created_by from spaces where id = ?::uuid").param(home).query(UUID.class).single().toString()).isEqualTo(raviId);
        mvc.perform(get("/api/core/households/" + home).with(ravi)).andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.spent").value(400_000));
    }

    private void expense(RequestPostProcessor who, String space, String account, String category, long amount) throws Exception {
        body(post("/api/core/spaces/" + space + "/transactions"), who, txn(account, category, amount));
    }

    private static String txn(String account, String category, long amount) {
        return """
                {"kind":"EXPENSE","amount":%d,"accountId":"%s","categoryId":"%s","occurredOn":"%s","description":"BigBasket"}
                """.formatted(amount, account, category, java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
    }

    private String body(MockHttpServletRequestBuilder request, RequestPostProcessor who, String json) throws Exception {
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request.with(who)).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    private static RequestPostProcessor user(String email) {
        String subject = UUID.randomUUID().toString();
        return jwt().jwt(j -> j.subject(subject).claim("email", email).claim("email_verified", true).claim("name", email.split("@")[0]));
    }
}
