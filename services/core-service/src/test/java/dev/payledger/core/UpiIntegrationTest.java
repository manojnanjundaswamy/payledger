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

/** A UPI id to be paid at: set, checked, removed, and seen by the people you share a group with. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UpiIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void aUpiIdIsCheckedAndSharedWithGroupMembers() throws Exception {
        String ravi = UUID.randomUUID() + "@example.com";
        var asha = user(UUID.randomUUID() + "@example.com");
        var r = user(ravi);
        mvc.perform(get("/api/core/me").with(r));
        setUpi(r, "\"ravi.k@okhdfcbank\"").andExpect(status().isOk()).andExpect(jsonPath("$.settings.upiId").value("ravi.k@okhdfcbank"));
        setUpi(r, "\"not a upi id\"").andExpect(status().isBadRequest());
        setUpi(r, "\"ravi@\"").andExpect(status().isBadRequest());
        // Other settings leave it alone.
        mvc.perform(patch("/api/core/me").with(r).contentType(MediaType.APPLICATION_JSON).content("{\"budgetStartDay\":5}"))
                .andExpect(jsonPath("$.settings.upiId").value("ravi.k@okhdfcbank"));

        String group = JsonPath.read(mvc.perform(post("/api/core/groups").with(asha).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Goa\"}")).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/core/groups/" + group + "/members").with(asha).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ravi\",\"email\":\"" + ravi + "\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.members[?(@.name == 'Ravi')].upiId").value(org.hamcrest.Matchers.contains("ravi.k@okhdfcbank")));
        mvc.perform(post("/api/core/groups/" + group + "/members").with(asha).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Placeholder\"}"))
                .andExpect(jsonPath("$.members[?(@.name == 'Placeholder')].upiId").value(org.hamcrest.Matchers.contains((Object) null)));

        setUpi(r, "\"\"").andExpect(status().isOk()).andExpect(jsonPath("$.settings.upiId").doesNotExist());
    }

    private org.springframework.test.web.servlet.ResultActions setUpi(RequestPostProcessor who, String value) throws Exception {
        return mvc.perform(patch("/api/core/me").with(who).contentType(MediaType.APPLICATION_JSON).content("{\"upiId\":" + value + "}"));
    }

    private static RequestPostProcessor user(String email) {
        String subject = UUID.randomUUID().toString();
        return jwt().jwt(j -> j.subject(subject).claim("email", email).claim("email_verified", true));
    }
}
