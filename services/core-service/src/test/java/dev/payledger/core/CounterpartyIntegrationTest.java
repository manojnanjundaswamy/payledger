package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who an entry was with: saved, found by person and by search, and kept when a rule re-saves the entry. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CounterpartyIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void anIncomeFromSomeoneIsFoundByThatPerson() throws Exception {
        String subject = UUID.randomUUID().toString();
        RequestPostProcessor me = jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
        String space = JsonPath.read(body(get("/api/core/me"), me, null), "$.spaces[0].id");
        String base = "/api/core/spaces/" + space;
        String bank = JsonPath.read(body(post(base + "/accounts"), me, "{\"kind\":\"BANK\",\"name\":\"SBI\",\"openingBalance\":0}"), "$.id");
        String other = JsonPath.<List<String>>read(body(get(base + "/categories"), me, null), "$[?(@.kind == 'INCOME' && @.name == 'Other')].id").getFirst();
        body(post(base + "/transactions"), me, """
                {"kind":"INCOME","amount":150000,"accountId":"%s","categoryId":"%s","occurredOn":"2026-10-14","description":"Money back","counterparty":" Neha Sharma "}
                """.formatted(bank, other));
        body(post(base + "/transactions"), me, """
                {"kind":"INCOME","amount":20000,"accountId":"%s","categoryId":"%s","occurredOn":"2026-10-14","description":"Cashback"}
                """.formatted(bank, other));

        mvc.perform(get(base + "/transactions").param("person", "neha sharma").with(me))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].counterparty").value("Neha Sharma"));
        mvc.perform(get(base + "/transactions").param("q", "neha").with(me)).andExpect(jsonPath("$.items.length()").value(1));
        // A rule that re-files it keeps who it was with.
        String rule = JsonPath.read(body(post(base + "/category-rules"), me, "{\"match\":\"Money back\",\"categoryId\":\"" + other + "\"}"), "$.id");
        body(post(base + "/category-rules/" + rule + "/apply"), me, null);
        mvc.perform(get(base + "/transactions").param("person", "Neha Sharma").with(me)).andExpect(jsonPath("$.items.length()").value(1));
    }

    private String body(MockHttpServletRequestBuilder request, RequestPostProcessor who, String json) throws Exception {
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request.with(who)).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }
}
