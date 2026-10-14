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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Deductions for the financial year: by category section or by a section tag, once each, against the limit. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TaxIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void aCategorysSectionAndASectionTagAddUpForTheFinancialYear() throws Exception {
        String subject = UUID.randomUUID().toString();
        RequestPostProcessor me = jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
        String space = JsonPath.read(body(get("/api/core/me"), me, null), "$.spaces[0].id");
        String base = "/api/core/spaces/" + space;
        String bank = JsonPath.read(body(post(base + "/accounts"), me, "{\"kind\":\"BANK\",\"name\":\"HDFC\",\"openingBalance\":0,\"openingDate\":\"2026-01-01\"}"), "$.id");
        String health = JsonPath.read(body(post(base + "/categories"), me, "{\"kind\":\"EXPENSE\",\"name\":\"Health insurance\",\"icon\":\"shield\",\"color\":\"series-2\"}"), "$.id");
        String other = JsonPath.<List<String>>read(body(get(base + "/categories"), me, null), "$[?(@.name == 'Other' && @.kind == 'EXPENSE')].id").getFirst();

        // "Health insurance" is suggested for 80D before it's mapped.
        mvc.perform(get(base + "/tax").param("fy", "2026").with(me))
                .andExpect(jsonPath("$.suggested[?(@.categoryId == '" + health + "')].section").value("80D"));
        body(put(base + "/tax/mappings/" + health), me, "{\"section\":\"80D\"}");

        txn(me, base, bank, health, 3_000_000, "2026-05-10", "Star Health", "");
        txn(me, base, bank, health, 500_000, "2026-03-31", "Last FY", "");          // March 2026: the year before
        txn(me, base, bank, other, 10_000_000, "2026-06-01", "ELSS", "\"80c\"");    // a tag, any case
        txn(me, base, bank, health, 100_000, "2026-07-01", "Check-up, as 80C", "\"80C\""); // the tag wins

        String report = body(get(base + "/tax").param("fy", "2026"), me, null);
        assertThat(JsonPath.<Object>read(report, "$.sections[?(@.code == '80D')].claimed").toString()).isEqualTo("[3000000]");
        assertThat(JsonPath.<Object>read(report, "$.sections[?(@.code == '80C')].claimed").toString()).isEqualTo("[10100000]");
        assertThat(JsonPath.<Object>read(report, "$.sections[?(@.code == '80C')].room").toString()).isEqualTo("[4900000]");
        assertThat(JsonPath.<Object>read(report, "$.from").toString()).isEqualTo("2026-04-01");

        mvc.perform(get(base + "/tax/export").param("fy", "2026").with(me)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("80D,2026-05-10,Star Health,Health insurance,30000.00")));
        mvc.perform(put(base + "/tax/mappings/" + health).with(me).contentType(MediaType.APPLICATION_JSON).content("{\"section\":\"99Z\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-section"));
        // Someone else's space: no.
        String stranger = UUID.randomUUID().toString();
        mvc.perform(get(base + "/tax").with(jwt().jwt(j -> j.subject(stranger).claim("email", stranger + "@example.com").claim("email_verified", true))))
                .andExpect(status().isNotFound());
    }

    private void txn(RequestPostProcessor me, String base, String bank, String category, long amount, String on, String description, String tags) throws Exception {
        body(post(base + "/transactions"), me, """
                {"kind":"EXPENSE","amount":%d,"accountId":"%s","categoryId":"%s","occurredOn":"%s","description":"%s","tags":[%s]}
                """.formatted(amount, bank, category, on, description, tags));
    }

    private String body(MockHttpServletRequestBuilder request, RequestPostProcessor who, String json) throws Exception {
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request.with(who)).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }
}
