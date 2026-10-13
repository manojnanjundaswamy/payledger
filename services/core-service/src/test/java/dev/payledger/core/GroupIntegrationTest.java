package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GroupIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    RequestPostProcessor me;
    RequestPostProcessor friend;
    String friendEmail;
    String group;

    @BeforeEach
    void users() throws Exception {
        me = user("Priya", UUID.randomUUID() + "@example.com");
        friendEmail = UUID.randomUUID() + "@example.com";
        friend = user("Rahul K", friendEmail);
        mvc.perform(get("/api/core/me").with(me));
        mvc.perform(get("/api/core/me").with(friend));
        group = id(mvc.perform(post("/api/core/groups").with(me).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Goa trip\",\"emoji\":\"🏖️\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.members[0].name").value("Priya"))
                .andExpect(jsonPath("$.members[0].role").value("OWNER"))
                .andExpect(jsonPath("$.members[0].you").value(true)));
    }

    @Test
    void onlyMembersSeeAGroup() throws Exception {
        mvc.perform(get("/api/core/groups").with(me))
                .andExpect(jsonPath("$[?(@.id == '" + group + "')].members").value(contains(1)));
        mvc.perform(get("/api/core/groups/" + group).with(friend))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("group-not-found"));
        mvc.perform(get("/api/core/groups").with(friend)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void peopleAreAddedByNameOrEmail() throws Exception {
        String stranger = UUID.randomUUID() + "@example.com";
        addMember(me, "Amit", null).andExpect(status().isOk());
        addMember(me, "Neha", stranger).andExpect(status().isOk());
        // An email with an account joins at once, under the name given here; its email isn't shown.
        addMember(me, "Rahul", friendEmail).andExpect(status().isOk())
                .andExpect(jsonPath("$.members[?(@.name == 'Amit')].joined").value(contains(false)))
                .andExpect(jsonPath("$.members[?(@.name == 'Neha')].email").value(contains(stranger)))
                .andExpect(jsonPath("$.members[?(@.name == 'Rahul')].joined").value(contains(true)))
                .andExpect(jsonPath("$.members[?(@.name == 'Rahul')].email").value(contains((Object) null)));
        mvc.perform(get("/api/core/groups/" + group).with(friend)).andExpect(status().isOk())
                .andExpect(jsonPath("$.members[?(@.you == true)].name").value(contains("Rahul")));
        addMember(me, "Neha again", stranger.toUpperCase()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("member-exists"));
        addMember(me, "Rahul again", friendEmail).andExpect(status().isConflict());
        addMember(me, "", null).andExpect(status().isBadRequest());
    }

    @Test
    void aJoinLinkAddsWhoeverOpensItOnceAndCanBeReplaced() throws Exception {
        String token = token(me);
        assertThat(token(me)).isEqualTo(token);
        mvc.perform(get("/api/core/groups/join/" + token).with(friend))
                .andExpect(jsonPath("$.groupName").value("Goa trip")).andExpect(jsonPath("$.members").value(1))
                .andExpect(jsonPath("$.alreadyMember").value(false));
        join(friend, token).andExpect(status().isOk()).andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[?(@.you == true)].name").value(contains("Rahul K")));
        join(friend, token).andExpect(status().isOk()).andExpect(jsonPath("$.members.length()").value(2));

        String fresh = JsonPath.read(mvc.perform(post("/api/core/groups/" + group + "/link/reset").with(friend))
                .andReturn().getResponse().getContentAsString(), "$.token");
        assertThat(fresh).isNotEqualTo(token).hasSize(32);
        var third = user("Third", UUID.randomUUID() + "@example.com");
        join(third, token).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("invalid-link"));
        join(third, fresh).andExpect(status().isOk()).andExpect(jsonPath("$.members.length()").value(3));
    }

    @Test
    void aPlaceholderInvitedByEmailBecomesThePersonWhenTheyJoinOrSignUp() throws Exception {
        String later = UUID.randomUUID() + "@example.com";
        // By link: the placeholder with this email is taken over, no second "Rahul".
        String placeholderEmail = UUID.randomUUID() + "@example.com";
        addMember(me, "Sam", placeholderEmail);
        var sam = user("Samuel", placeholderEmail);
        mvc.perform(get("/api/core/me").with(sam));
        // Sam had no account when invited, so signing up claimed the place already.
        mvc.perform(get("/api/core/groups/" + group).with(sam)).andExpect(status().isOk())
                .andExpect(jsonPath("$.members[?(@.you == true)].name").value(contains("Sam")));
        join(sam, token(me)).andExpect(jsonPath("$.members.length()").value(2));

        addMember(me, "Later", later);
        var newcomer = user("New Person", later);
        mvc.perform(get("/api/core/groups").with(newcomer)).andExpect(jsonPath("$[0].id").value(group));
    }

    @Test
    void anyoneCanLeaveButTheOwnerAndOnlyTheOwnerRemovesOthers() throws Exception {
        join(friend, token(me));
        String amit = memberId(addMember(me, "Amit", null), "Amit");
        String rahul = memberId(mvc.perform(get("/api/core/groups/" + group).with(me)), "Rahul K");
        String owner = memberId(mvc.perform(get("/api/core/groups/" + group).with(me)), "Priya");

        mvc.perform(delete("/api/core/groups/" + group + "/members/" + amit).with(friend))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("owner-only"));
        mvc.perform(delete("/api/core/groups/" + group + "/members/" + owner).with(me))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("owner-cannot-leave"));
        mvc.perform(delete("/api/core/groups/" + group + "/members/" + amit).with(me)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/core/groups/" + group + "/members/" + rahul).with(friend)).andExpect(status().isNoContent());
        mvc.perform(get("/api/core/groups/" + group).with(friend)).andExpect(status().isNotFound());
        mvc.perform(get("/api/core/groups/" + group).with(me)).andExpect(jsonPath("$.members.length()").value(1));
    }

    @Test
    void theOwnerArchivesAndAnArchivedGroupsLinkStopsWorking() throws Exception {
        String token = token(me);
        join(friend, token);
        mvc.perform(patch("/api/core/groups/" + group).with(friend).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Goa 2026\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Goa 2026"));
        mvc.perform(patch("/api/core/groups/" + group).with(friend).contentType(MediaType.APPLICATION_JSON).content("{\"archived\":true}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("owner-only"));
        mvc.perform(patch("/api/core/groups/" + group).with(me).contentType(MediaType.APPLICATION_JSON).content("{\"archived\":true}"))
                .andExpect(jsonPath("$.archived").value(true));
        join(user("Late", UUID.randomUUID() + "@example.com"), token).andExpect(status().isNotFound());
    }

    @Test
    void joiningManyTimesAtOnceAddsOnce() throws Exception {
        String token = token(me);
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> join(friend, token).andReturn().getResponse().getStatus()));
            }
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(200);
            }
        }
        assertThat(jdbc.sql("select count(*) from group_members where group_id = ? and left_at is null")
                .param(UUID.fromString(group)).query(Long.class).single()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- helpers

    private static RequestPostProcessor user(String name, String email) {
        String subject = UUID.randomUUID().toString();
        return jwt().jwt(j -> j.subject(subject).claim("email", email).claim("email_verified", true).claim("name", name));
    }

    private ResultActions addMember(RequestPostProcessor who, String name, String email) throws Exception {
        String body = email == null ? "{\"name\":\"" + name + "\"}" : "{\"name\":\"" + name + "\",\"email\":\"" + email + "\"}";
        return mvc.perform(post("/api/core/groups/" + group + "/members").with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String token(RequestPostProcessor who) throws Exception {
        return JsonPath.read(mvc.perform(get("/api/core/groups/" + group + "/link").with(who)).andReturn().getResponse().getContentAsString(), "$.token");
    }

    private ResultActions join(RequestPostProcessor who, String token) throws Exception {
        // A first request provisions the user, as the gateway's first call would.
        mvc.perform(get("/api/core/me").with(who));
        return mvc.perform(post("/api/core/groups/join/" + token).with(who));
    }

    private static String memberId(ResultActions result, String name) throws Exception {
        return JsonPath.<List<String>>read(result.andReturn().getResponse().getContentAsString(), "$.members[?(@.name == '" + name + "')].id").getFirst();
    }

    private static String id(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }
}
