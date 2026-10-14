package dev.payledger.core;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tenant isolation, proven by brute force: a second user (B) calls every endpoint, present and future, with the first
 * user's (A) ids, both in A's space and smuggled into B's own space and request bodies. Nothing may succeed, nothing
 * of A's may appear in any answer, and A's data must be exactly as before.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TenantIsolationIntegrationTest {

    /** The same fake ledger everywhere (locally a running ledger would otherwise answer, in CI none would). */
    @DynamicPropertySource
    static void ledger(DynamicPropertyRegistry registry) {
        registry.add("payledger.ledger.url", FakeLedger::url);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    /** A's ids by path-variable name; "id" depends on the path. */
    final Map<String, String> a = new LinkedHashMap<>();
    String secret;
    RequestPostProcessor alice;
    RequestPostProcessor bob;
    String bobSpace;
    String bobGroup;

    @BeforeEach
    void twoTenants() throws Exception {
        FakeLedger.DOWN.set(false);
        secret = "SECRET" + UUID.randomUUID().toString().substring(0, 8);
        alice = user();
        bob = user();
        String space = JsonPath.read(me(alice), "$.spaces[0].id");
        a.put("spaceId", space);
        String account = id(post("/api/core/spaces/" + space + "/accounts", alice, "{\"kind\":\"BANK\",\"name\":\"" + secret + " bank\"}"));
        a.put("accountId", account);
        String category = id(post("/api/core/spaces/" + space + "/categories", alice,
                "{\"kind\":\"EXPENSE\",\"name\":\"" + secret + " cat\",\"icon\":\"tag\",\"color\":\"series-1\"}"));
        a.put("categoryId", category);
        a.put("transaction", id(post("/api/core/spaces/" + space + "/transactions", alice, txn(account, category))));
        a.put("commitment", id(post("/api/core/spaces/" + space + "/commitments", alice, bill(account, category))));
        a.put("rule", id(post("/api/core/spaces/" + space + "/category-rules", alice, "{\"match\":\"" + secret.toLowerCase() + "\",\"categoryId\":\"" + category + "\"}")));
        a.put("goal", id(post("/api/core/spaces/" + space + "/goals", alice, "{\"name\":\"" + secret + " goal\",\"target\":100000}")));
        post("/api/core/spaces/" + space + "/goals/" + a.get("goal") + "/contributions", alice, "{\"amount\":5000,\"note\":\"" + secret + "\"}");
        a.put("contributionId", JsonPath.<List<String>>read(mvc.perform(get("/api/core/spaces/" + space + "/goals/" + a.get("goal") + "/contributions").with(alice))
                .andReturn().getResponse().getContentAsString(), "$[*].id").getFirst());
        mvc.perform(request(HttpMethod.PUT, "/api/core/spaces/" + space + "/budgets/" + category).with(alice)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":500000,\"rollover\":false}")).andExpect(status().isOk());
        String imported = post("/api/core/spaces/" + space + "/imports", alice, importBody(account, category));
        a.put("importId", JsonPath.read(imported, "$.importId"));

        String group = id(post("/api/core/groups", alice, "{\"name\":\"" + secret + " trip\"}"));
        a.put("groupId", group);
        String members = post("/api/core/groups/" + group + "/members", alice, "{\"name\":\"" + secret + " friend\"}");
        String friend = JsonPath.read(members, "$.members[?(@.name == '" + secret + " friend')].id[0]") instanceof String s ? s
                : ((List<?>) JsonPath.read(members, "$.members[?(@.name == '" + secret + " friend')].id")).get(0).toString();
        String self = ((List<?>) JsonPath.read(members, "$.members[?(@.you == true)].id")).get(0).toString();
        a.put("memberId", friend);
        a.put("selfMember", self);
        a.put("expenseId", id(post("/api/core/groups/" + group + "/expenses", alice, groupExpense(self, friend))));
        a.put("settlementId", id(post("/api/core/groups/" + group + "/settlements", alice,
                "{\"fromMemberId\":\"" + friend + "\",\"toMemberId\":\"" + self + "\",\"amount\":100,\"occurredOn\":\"2026-10-06\",\"note\":\"" + secret + "\"}")));

        bobSpace = JsonPath.read(me(bob), "$.spaces[0].id");
        bobGroup = id(post("/api/core/groups", bob, "{\"name\":\"Bob's flat\"}"));
    }

    @Test
    void aStrangerCanNeitherSeeNorChangeAnythingOfAnotherTenant() throws Exception {
        String before = fingerprint();
        List<String> problems = new ArrayList<>();
        for (Endpoint e : endpoints()) {
            String path = fill(e.pattern(), false);
            if (path == null) {
                continue;
            }
            for (String body : e.method() == RequestMethod.GET ? List.of("") : attackBodies(false)) {
                check(e, path, body, problems);
            }
        }
        assertThat(problems).isEmpty();
        assertThat(fingerprint()).isEqualTo(before);
    }

    @Test
    void anotherTenantsIdsCantBeSmuggledIntoYourOwnSpaceOrGroup() throws Exception {
        String before = fingerprint();
        List<String> problems = new ArrayList<>();
        for (Endpoint e : endpoints()) {
            String path = fill(e.pattern(), true);
            if (path == null) {
                continue;
            }
            boolean pathHasAlice = a.values().stream().anyMatch(path::contains);
            if (e.method() == RequestMethod.GET) {
                // Your own lists may answer, but never with anything of A's in them.
                leakCheck(e, path, "", problems, !pathHasAlice);
                continue;
            }
            for (String body : attackBodies(true)) {
                boolean bodyHasAlice = a.values().stream().anyMatch(body::contains);
                if (!pathHasAlice && !bodyHasAlice) {
                    continue; // B changing B's own things is allowed
                }
                MockHttpServletResponse r = call(e.method(), path, body);
                if (r.getStatus() >= 500 || (pathHasAlice && r.getStatus() < 400)) {
                    problems.add(e.method() + " " + e.pattern() + " -> " + r.getStatus() + " with " + body);
                }
                leak(e, r, problems);
            }
        }
        // A write into B's own space that ignored A's ids is fine; one that stored them is not.
        problems.addAll(bobsDataNamingAlice());
        assertThat(problems).isEmpty();
        assertThat(fingerprint()).isEqualTo(before);
    }

    // ---------------------------------------------------------------- the attack

    record Endpoint(RequestMethod method, String pattern) {
    }

    /** Every tenant-scoped endpoint: spaces and groups. Admin, profile, plan and join-by-link are covered elsewhere. */
    private List<Endpoint> endpoints() {
        List<Endpoint> result = new ArrayList<>();
        mappings.getHandlerMethods().keySet().forEach(info -> info.getPatternValues().stream()
                .filter(p -> p.startsWith("/api/core/spaces/") || (p.startsWith("/api/core/groups/") && !p.contains("/join/")))
                .forEach(p -> (info.getMethodsCondition().getMethods().isEmpty() ? java.util.Set.of(RequestMethod.GET)
                        : info.getMethodsCondition().getMethods()).forEach(m -> result.add(new Endpoint(m, p)))));
        assertThat(result).hasSizeGreaterThan(40);
        return result;
    }

    /** Fills path variables with A's ids; {@code own} keeps B's own space or group as the container. */
    private String fill(String pattern, boolean own) {
        Matcher m = Pattern.compile("\\{([^}]+)}").matcher(pattern);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = switch (name) {
                case "spaceId" -> own ? bobSpace : a.get("spaceId");
                case "groupId" -> own ? bobGroup : a.get("groupId");
                case "id" -> pattern.contains("/commitments/") ? a.get("commitment") : pattern.contains("/category-rules/") ? a.get("rule")
                        : pattern.contains("/goals/") ? a.get("goal") : a.get("transaction");
                default -> a.get(name);
            };
            if (value == null) {
                throw new IllegalStateException("no id for {" + name + "} in " + pattern + ": add it to the test");
            }
            m.appendReplacement(out, value);
        }
        m.appendTail(out);
        String path = out.toString();
        // In "own" mode a path naming only B's container has nothing of A's but what the body carries.
        return path;
    }

    /** Valid-looking bodies for every kind of write, all pointing at A's things, so validation can't hide a missing check. */
    private List<String> attackBodies(boolean own) {
        String acc = a.get("accountId");
        String cat = a.get("categoryId");
        // B's own text is "HIJACK"; A's secret in an answer can then only come from A's data.
        return attackBodiesWithAliceText(acc, cat).stream().map(b -> b.replace(secret, "HIJACK")).toList();
    }

    private List<String> attackBodiesWithAliceText(String acc, String cat) {
        return List.of(
                txn(acc, cat),
                "{\"version\":1," + txn(acc, cat).strip().substring(1),
                bill(acc, cat),
                "{\"version\":1," + bill(acc, cat).strip().substring(1),
                "{\"kind\":\"EXPENSE\",\"name\":\"Hijack\",\"icon\":\"tag\",\"color\":\"series-1\",\"parentId\":\"" + cat + "\"}",
                "{\"name\":\"Hijack\",\"icon\":\"tag\",\"color\":\"series-1\",\"parentId\":\"" + cat + "\",\"hidden\":false}",
                "{\"name\":\"Hijack\",\"archived\":true}",
                "{\"amount\":1,\"rollover\":false}",
                "{\"into\":\"" + cat + "\"}",
                "{\"dueDate\":\"2026-11-01\"}",
                importBody(acc, cat),
                groupExpense(a.get("selfMember"), a.get("memberId")),
                "{\"version\":1," + groupExpense(a.get("selfMember"), a.get("memberId")).strip().substring(1),
                "{\"fromMemberId\":\"" + a.get("memberId") + "\",\"toMemberId\":\"" + a.get("selfMember") + "\",\"amount\":1,\"occurredOn\":\"2026-10-06\"}",
                "{\"name\":\"Hijack\",\"email\":\"hijack@example.com\"}",
                "{}");
    }

    private void check(Endpoint e, String path, String body, List<String> problems) throws Exception {
        MockHttpServletResponse r = call(e.method(), path, body);
        if (r.getStatus() < 400 || r.getStatus() >= 500) {
            problems.add(e.method() + " " + e.pattern() + " -> " + r.getStatus() + " with " + body);
        }
        // 400 is a missing query parameter, checked before anything is looked up: it tells nothing.
        if (e.method() == RequestMethod.GET && r.getStatus() != 404 && r.getStatus() != 403 && r.getStatus() != 400) {
            problems.add(e.method() + " " + e.pattern() + " -> " + r.getStatus() + " (expected 404)");
        }
        leak(e, r, problems);
    }

    private void leakCheck(Endpoint e, String path, String body, List<String> problems, boolean mayAnswer) throws Exception {
        MockHttpServletResponse r = call(e.method(), path, body);
        if (r.getStatus() >= 500 || (!mayAnswer && r.getStatus() < 400)) {
            problems.add(e.method() + " " + e.pattern() + " -> " + r.getStatus());
        }
        leak(e, r, problems);
    }

    private void leak(Endpoint e, MockHttpServletResponse r, List<String> problems) throws Exception {
        String text = r.getContentAsString();
        if (text.contains(secret)) {
            problems.add(e.method() + " " + e.pattern() + " leaked A's names");
        }
        for (var entry : a.entrySet()) {
            // A 404 may echo nothing; any answer naming A's ids (other than the path itself) is a leak.
            if (text.contains(entry.getValue()) && r.getStatus() < 400) {
                problems.add(e.method() + " " + e.pattern() + " leaked A's " + entry.getKey());
            }
        }
    }

    private MockHttpServletResponse call(RequestMethod method, String path, String body) throws Exception {
        var req = request(HttpMethod.valueOf(method.name()), path).with(bob);
        if (!body.isEmpty()) {
            req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(req).andReturn().getResponse();
    }

    /** Rows of B's that point at anything of A's: an account, a category, a group member. */
    private List<String> bobsDataNamingAlice() {
        List<String> found = new ArrayList<>();
        for (String table : List.of("money_accounts", "categories", "transactions", "commitments", "budget_limits", "imports", "category_rules", "goals")) {
            String rows = jdbc.sql("select coalesce(string_agg(t::text, '|'), '') from " + table + " t where space_id = cast(:s as uuid)")
                    .param("s", bobSpace).query(String.class).single();
            a.forEach((k, v) -> {
                if (rows.contains(v)) {
                    found.add("B's " + table + " points at A's " + k);
                }
            });
        }
        for (String table : List.of("group_members", "group_expenses", "group_settlements")) {
            String rows = jdbc.sql("select coalesce(string_agg(t::text, '|'), '') from " + table + " t where group_id = cast(:g as uuid)")
                    .param("g", bobGroup).query(String.class).single();
            String parts = jdbc.sql("""
                            select coalesce(string_agg(p::text, '|'), '') from group_expense_parts p
                            join group_expenses e on e.id = p.expense_id where e.group_id = cast(:g as uuid)
                            """).param("g", bobGroup).query(String.class).single();
            a.forEach((k, v) -> {
                if (rows.contains(v) || parts.contains(v)) {
                    found.add("B's " + table + " points at A's " + k);
                }
            });
        }
        return found;
    }

    /** Everything of A's, as one hash per table: any change shows. */
    private String fingerprint() {
        String space = a.get("spaceId");
        String group = a.get("groupId");
        StringBuilder sb = new StringBuilder();
        for (String table : List.of("money_accounts", "categories", "transactions", "commitments", "commitment_payments", "budget_limits", "imports",
                "category_rules", "goals", "goal_contributions")) {
            String where = table.equals("commitment_payments")
                    ? "commitment_id in (select id from commitments where space_id = cast(:s as uuid))"
                    : table.equals("goal_contributions") ? "goal_id in (select id from goals where space_id = cast(:s as uuid))"
                    : "space_id = cast(:s as uuid)";
            sb.append(table).append('=').append(jdbc.sql("select coalesce(md5(string_agg(t::text, '|' order by t::text)), '-') from " + table + " t where " + where)
                    .param("s", space).query(String.class).single()).append('\n');
        }
        for (String table : List.of("groups", "group_members", "group_expenses", "group_settlements", "group_links")) {
            String where = table.equals("groups") ? "id = cast(:g as uuid)" : "group_id = cast(:g as uuid)";
            sb.append(table).append('=').append(jdbc.sql("select coalesce(md5(string_agg(t::text, '|' order by t::text)), '-') from " + table + " t where " + where)
                    .param("g", group).query(String.class).single()).append('\n');
        }
        sb.append("outbox=").append(jdbc.sql("select count(*) from outbox_events").query(Long.class).single());
        return sb.toString();
    }

    // ---------------------------------------------------------------- helpers

    private String txn(String account, String category) {
        return """
                {"kind":"EXPENSE","amount":12345,"accountId":"%s","toAccountId":null,"categoryId":"%s","occurredOn":"2026-10-05","description":"%s txn","tags":[]}
                """.formatted(account, category, secret);
    }

    private String bill(String account, String category) {
        return """
                {"kind":"BILL","name":"%s bill","amount":100000,"accountId":"%s","categoryId":"%s","everyMonths":1,"firstDue":"2026-11-01"}
                """.formatted(secret, account, category);
    }

    private String importBody(String account, String category) {
        String csv = "date,amount,type,account,category,description\n2026-10-03,250,EXPENSE,Bank,Cat," + secret + " imported\n";
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of(
                "fileName", "x.csv", "csv", csv, "accounts", Map.of("bank", account), "categories", Map.of("EXPENSE:cat", category)));
    }

    private String groupExpense(String payer, String other) {
        return """
                {"description":"%s dinner","amount":4000,"occurredOn":"2026-10-05","category":"Food","paidBy":[{"memberId":"%s","value":4000}],"split":"EQUAL","shares":[{"memberId":"%s"},{"memberId":"%s"}]}
                """.formatted(secret, payer, payer, other);
    }

    private String me(RequestPostProcessor who) throws Exception {
        return mvc.perform(get("/api/core/me").with(who)).andReturn().getResponse().getContentAsString();
    }

    private String post(String path, RequestPostProcessor who, String body) throws Exception {
        MockHttpServletResponse r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).with(who)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertThat(r.getStatus()).as(path + " " + r.getContentAsString()).isBetween(200, 299);
        return r.getContentAsString();
    }

    private static String id(String json) {
        return JsonPath.read(json, "$.id");
    }

    private static RequestPostProcessor user() {
        String subject = UUID.randomUUID().toString();
        return jwt().jwt(j -> j.subject(subject).claim("email", subject + "@example.com").claim("email_verified", true));
    }
}
