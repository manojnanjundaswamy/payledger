package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Staff managing users: roles, creating, disabling, Premium, emails, and the audit log. Keycloak is faked. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "payledger.admin.owners=chief@payledger.example")
@AutoConfigureMockMvc
class AdminUserIntegrationTest {

    static final RequestPostProcessor OWNER = user("chief@payledger.example", UUID.randomUUID().toString());

    @DynamicPropertySource
    static void keycloak(DynamicPropertyRegistry registry) {
        registry.add("payledger.identity.url", FakeKeycloak::url);
        registry.add("payledger.identity.client-secret", () -> FakeKeycloak.SECRET);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void eachRoleCanDoOnlyItsJob() throws Exception {
        String customer = id(newUser());
        var support = staffMember("SUPPORT");
        var analyst = staffMember("ANALYST");
        var admin = staffMember("ADMIN");

        // Support finds users, sends emails and signs them out; it can't create, change plans or read the audit log.
        mvc.perform(get("/api/core/admin/users").with(support)).andExpect(status().isOk());
        mvc.perform(get("/api/core/admin/users/" + customer).with(support)).andExpect(status().isOk());
        mvc.perform(post("/api/core/admin/users/" + customer + "/sign-out").with(support)).andExpect(status().isNoContent());
        create(support, unique() + "@example.com").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("missing-permission"))
                .andExpect(jsonPath("$.detail").value("the support role can't do this (needs users manage)"));
        grant(support, customer, LocalDate.now().plusDays(10)).andExpect(status().isForbidden());
        mvc.perform(get("/api/core/admin/audit").with(support)).andExpect(status().isForbidden());

        // An analyst sees the numbers, never a person.
        mvc.perform(get("/api/core/admin/overview").with(analyst)).andExpect(status().isOk());
        mvc.perform(get("/api/core/admin/users").with(analyst)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("missing-permission"));

        // An admin manages users and plans, but not staff: disabling a staff member needs the owner.
        grant(admin, customer, LocalDate.now().plusDays(10)).andExpect(status().isOk());
        String supportId = id(support);
        disable(admin, supportId, true).andExpect(status().isForbidden());
        disable(OWNER, supportId, true).andExpect(status().isOk());
    }

    @Test
    void creatingAUserMakesTheSignInAccountEmailsThemAndTheirFirstSignInFindsIt() throws Exception {
        String email = unique() + "@Example.com";
        String created = create(OWNER, email).andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.user.plan").value("FREE"))
                .andReturn().getResponse().getContentAsString();
        String subject = JsonPath.read(created, "$.subject");
        assertThat(FakeKeycloak.callsFor(subject))
                .anySatisfy(c -> assertThat(c).startsWith("CREATE " + email.toLowerCase()).contains("\"emailVerified\":false"))
                .anySatisfy(c -> assertThat(c).contains("/execute-actions-email").contains("VERIFY_EMAIL").contains("UPDATE_PASSWORD"));
        // Staff never set a password: nothing in the calls carries one.
        assertThat(FakeKeycloak.callsFor(subject)).noneMatch(c -> c.contains("credentials") || c.contains("password\""));

        var them = user(email.toLowerCase(), subject);
        mvc.perform(get("/api/core/me").with(them)).andExpect(jsonPath("$.id").value((String) JsonPath.read(created, "$.user.id")));

        create(OWNER, email).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("email-in-use"));
        create(OWNER, "not an email").andExpect(status().isBadRequest());
    }

    @Test
    void aNewUserCanStartWithPremium() throws Exception {
        LocalDate until = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusMonths(3);
        create(OWNER, unique() + "@example.com", "{\"email\":\"%s\",\"displayName\":\"Asha\",\"premiumUntil\":\"" + until + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.plan").value("GRANTED"));
    }

    @Test
    void aDisabledUserCantUseTheAppUntilEnabledAgain() throws Exception {
        var customer = newUser();
        String id = id(customer);
        String subject = jdbc.sql("select subject from users where id = ?").param(UUID.fromString(id)).query(String.class).single();

        disable(OWNER, id, true).andExpect(status().isOk()).andExpect(jsonPath("$.user.disabled").value(true));
        mvc.perform(get("/api/core/me").with(customer)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("account-disabled"));
        assertThat(FakeKeycloak.callsFor(subject))
                .anySatisfy(c -> assertThat(c).startsWith("PUT").contains("\"enabled\":false"))
                .anySatisfy(c -> assertThat(c).startsWith("POST").endsWith("/logout "));
        // Disabling twice changes nothing more.
        int calls = FakeKeycloak.callsFor(subject).size();
        disable(OWNER, id, true).andExpect(status().isOk());
        assertThat(FakeKeycloak.callsFor(subject)).hasSize(calls);

        disable(OWNER, id, false).andExpect(status().isOk()).andExpect(jsonPath("$.user.disabled").value(false));
        mvc.perform(get("/api/core/me").with(customer)).andExpect(status().isOk());
        assertThat(FakeKeycloak.callsFor(subject)).anySatisfy(c -> assertThat(c).contains("\"enabled\":true"));
    }

    @Test
    void nobodyDisablesThemselvesOrAConfiguredOwner() throws Exception {
        var admin = staffMember("ADMIN");
        disable(admin, id(admin), true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("self"));
        disable(admin, id(OWNER), true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("protected-user"));
    }

    @Test
    void staffGiveAndEndPremium() throws Exception {
        var customer = newUser();
        String id = id(customer);
        LocalDate until = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(30);
        grant(OWNER, id, until).andExpect(status().isOk()).andExpect(jsonPath("$.user.plan").value("GRANTED"));
        mvc.perform(get("/api/core/plan").with(customer))
                .andExpect(jsonPath("$.plan").value("PREMIUM")).andExpect(jsonPath("$.source").value("GRANTED"));

        mvc.perform(delete("/api/core/admin/users/" + id + "/premium").with(OWNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.plan").value("FREE"));
        mvc.perform(get("/api/core/plan").with(customer)).andExpect(jsonPath("$.plan").value("FREE"));
        mvc.perform(delete("/api/core/admin/users/" + id + "/premium").with(OWNER))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("not-ending"));

        grant(OWNER, id, LocalDate.now().minusDays(2)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("bad-date"));
        grant(OWNER, id, LocalDate.now().plusYears(6)).andExpect(status().isConflict());
    }

    @Test
    void usersAreFoundByEmailOrNameAndPlan() throws Exception {
        String tag = unique();
        var customer = user(tag + "@example.com", UUID.randomUUID().toString());
        String id = id(customer);
        mvc.perform(get("/api/core/admin/users").param("q", tag.substring(2, 10).toUpperCase()).with(OWNER))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.users[0].id").value(id));
        // "%" and "_" are searched for, not wildcards.
        mvc.perform(get("/api/core/admin/users").param("q", "%").with(OWNER)).andExpect(jsonPath("$.total").value(0));

        grant(OWNER, id, LocalDate.now().plusDays(5));
        mvc.perform(get("/api/core/admin/users").param("q", tag).param("plan", "PREMIUM").with(OWNER)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/core/admin/users").param("q", tag).param("plan", "FREE").with(OWNER)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/core/admin/users").param("plan", "GOLD").with(OWNER)).andExpect(status().isBadRequest());
    }

    @Test
    void aUsersPageShowsCountsNeverMoneyAndItsHistory() throws Exception {
        var customer = newUser();
        String id = id(customer);
        String space = JsonPath.read(mvc.perform(get("/api/core/me").with(customer)).andReturn().getResponse().getContentAsString(), "$.spaces[0].id");
        mvc.perform(post("/api/core/spaces/" + space + "/accounts").with(customer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"BANK\",\"name\":\"Secret savings 98765\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/core/admin/users/" + id + "/emails").with(OWNER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"PASSWORD_RESET\"}")).andExpect(status().isNoContent());

        String detail = mvc.perform(get("/api/core/admin/users/" + id).with(OWNER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.accounts").value(1))
                .andExpect(jsonPath("$.usage.activeDays30").value(1))
                .andExpect(jsonPath("$.history[0].action").value("user.email.password-reset"))
                .andExpect(jsonPath("$.history[0].actorEmail").value("chief@payledger.example"))
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("Secret savings").doesNotContain("amount");

        mvc.perform(get("/api/core/admin/audit").with(OWNER)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("user.email.password-reset"));
        mvc.perform(get("/api/core/admin/users/" + UUID.randomUUID()).with(OWNER)).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- helpers

    private RequestPostProcessor staffMember(String role) throws Exception {
        var member = newUser();
        jdbc.sql("insert into staff_roles (user_id, role) values (?, ?)").param(UUID.fromString(id(member))).param(role).update();
        return member;
    }

    private String id(RequestPostProcessor who) throws Exception {
        return JsonPath.read(mvc.perform(get("/api/core/me").with(who)).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private ResultActions create(RequestPostProcessor who, String email) throws Exception {
        return create(who, email, "{\"email\":\"%s\",\"displayName\":\"New person\"}");
    }

    private ResultActions create(RequestPostProcessor who, String email, String template) throws Exception {
        return mvc.perform(post("/api/core/admin/users").with(who).contentType(MediaType.APPLICATION_JSON).content(template.formatted(email)));
    }

    private ResultActions grant(RequestPostProcessor who, String id, LocalDate until) throws Exception {
        return mvc.perform(put("/api/core/admin/users/" + id + "/premium").with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"until\":\"" + until + "\"}"));
    }

    private ResultActions disable(RequestPostProcessor who, String id, boolean disabled) throws Exception {
        return mvc.perform(put("/api/core/admin/users/" + id + "/disabled").with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"disabled\":" + disabled + "}"));
    }

    private static String unique() {
        return "u" + UUID.randomUUID().toString().replace("-", "");
    }

    private static RequestPostProcessor newUser() {
        return user(unique() + "@example.com", UUID.randomUUID().toString());
    }

    private static RequestPostProcessor user(String email, String subject) {
        return jwt().jwt(j -> j.subject(subject).claim("email", email).claim("email_verified", true));
    }
}
