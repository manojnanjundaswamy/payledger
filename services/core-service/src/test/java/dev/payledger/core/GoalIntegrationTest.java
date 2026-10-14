package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Savings goals: progress from money added, the monthly amount still needed, on track or behind, and the Free limit. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GoalIntegrationTest {

    @Autowired MockMvc mvc;

    RequestPostProcessor me;
    String space;

    @BeforeEach
    void newUser() throws Exception {
        String subject = UUID.randomUUID().toString();
        me = jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
        space = JsonPath.read(mvc.perform(get("/api/core/me").with(me)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
    }

    @Test
    void aGoalTracksMoneyAddedAndSaysWhatItStillNeedsEachMonth() throws Exception {
        // Ten calendar months including this one.
        LocalDate by = LocalDate.now().plusMonths(9).withDayOfMonth(28);
        String goal = id(create("{\"name\":\"Goa trip\",\"emoji\":\"🏖️\",\"target\":10000000,\"targetDate\":\"" + by + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saved").value(0)).andExpect(jsonPath("$.perMonth").value(1_000_000))
                .andExpect(jsonPath("$.status").value("ON_TRACK")));

        contribute(goal, "{\"amount\":4000000,\"note\":\"Diwali bonus\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.saved").value(4_000_000)).andExpect(jsonPath("$.perMonth").value(600_000));
        // Taking out more than was put in is refused; taking some out is fine.
        contribute(goal, "{\"amount\":-5000000}").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-amount"));
        contribute(goal, "{\"amount\":-1000000}").andExpect(jsonPath("$.saved").value(3_000_000));
        String latest = JsonPath.<List<String>>read(mvc.perform(get(path("/goals/" + goal + "/contributions")).with(me))
                .andExpect(jsonPath("$.length()").value(2)).andReturn().getResponse().getContentAsString(), "$[*].id").getFirst();
        mvc.perform(delete(path("/goals/" + goal + "/contributions/" + latest)).with(me)).andExpect(status().isNoContent());

        // Reaching the target.
        contribute(goal, "{\"amount\":6000000}").andExpect(jsonPath("$.status").value("REACHED")).andExpect(jsonPath("$.perMonth").doesNotExist());

        // Free: one goal at a time; archiving frees the place, and bringing one back is checked again.
        create("{\"name\":\"Phone\",\"target\":5000000}").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan-limit"));
        update(goal, "{\"version\":1,\"name\":\"Goa trip\",\"target\":10000000,\"archived\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(true));
        String phone = id(create("{\"name\":\"Phone\",\"target\":5000000}").andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("OPEN")));
        update(goal, "{\"version\":2,\"name\":\"Goa trip\",\"target\":10000000,\"archived\":false}").andExpect(status().isForbidden());
        update(goal, "{\"version\":1,\"name\":\"Goa\",\"target\":1}").andExpect(status().isConflict());
        mvc.perform(get(path("/goals")).with(me)).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(path("/goals")).with(me).param("includeArchived", "true")).andExpect(jsonPath("$.length()").value(2));

        mvc.perform(delete(path("/goals/" + phone)).with(me)).andExpect(status().isNoContent());
        create("{\"name\":\" \",\"target\":5000}").andExpect(status().isBadRequest());
    }

    private ResultActions create(String body) throws Exception {
        return mvc.perform(post(path("/goals")).with(me).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions update(String goal, String body) throws Exception {
        return mvc.perform(patch(path("/goals/" + goal)).with(me).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions contribute(String goal, String body) throws Exception {
        return mvc.perform(post(path("/goals/" + goal + "/contributions")).with(me).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String path(String rest) {
        return "/api/core/spaces/" + space + rest;
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
