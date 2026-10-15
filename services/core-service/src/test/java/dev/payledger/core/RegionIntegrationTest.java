package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Setting the region: a new person's empty space follows their currency; recorded money never changes currency. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RegionIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void anEmptySpaceFollowsTheChosenCurrencyAndAUsedOneKeepsItsOwn() throws Exception {
        RequestPostProcessor me = user();
        mvc.perform(get("/api/core/me").with(me)).andExpect(jsonPath("$.settings.country").doesNotExist())
                .andExpect(jsonPath("$.settings.fyStartMonth").value(4)).andExpect(jsonPath("$.spaces[0].currency").value("INR"));
        mvc.perform(patch("/api/core/me").with(me).contentType(MediaType.APPLICATION_JSON).content("""
                        {"country":"AE","currency":"AED","locale":"en-AE","timeZone":"Asia/Dubai","fyStartMonth":1,"budgetStartDay":28}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.settings.country").value("AE")).andExpect(jsonPath("$.settings.fyStartMonth").value(1))
                .andExpect(jsonPath("$.spaces[0].currency").value("AED"));

        // Once there's money in it, the space keeps its currency.
        String space = JsonPath.read(mvc.perform(get("/api/core/me").with(me)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
        mvc.perform(post("/api/core/spaces/" + space + "/accounts").with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"BANK\",\"name\":\"ENBD\"}")).andExpect(status().isCreated());
        mvc.perform(patch("/api/core/me").with(me).contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"INR\"}"))
                .andExpect(jsonPath("$.settings.currency").value("INR")).andExpect(jsonPath("$.spaces[0].currency").value("AED"));

        mvc.perform(patch("/api/core/me").with(me).contentType(MediaType.APPLICATION_JSON).content("{\"fyStartMonth\":13}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/core/me").with(me).contentType(MediaType.APPLICATION_JSON).content("{\"country\":\"india\"}"))
                .andExpect(status().isBadRequest());
    }

    private static RequestPostProcessor user() {
        String subject = UUID.randomUUID().toString();
        return jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
    }
}
