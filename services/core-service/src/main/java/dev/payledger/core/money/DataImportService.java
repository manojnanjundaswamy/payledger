package dev.payledger.core.money;

import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.common.Uuid7;
import dev.payledger.core.space.SpaceAccess;
import dev.payledger.core.space.SpaceAccess.Space;
import dev.payledger.core.user.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Brings a PayLedger export back into an account: after deleting and signing up again, or moving to a new email. It
 * restores your personal money (settings, categories, accounts with their opening balances, transactions through the
 * ledger, budgets, bills and their payments, goals, rules and tax sections) with new ids and every link kept. Groups
 * and households are shared with other people, so they can't come back from one person's file; the result says so.
 * Only into an account with no money in it yet, so nothing is doubled.
 */
@Service
public class DataImportService {

    private final JdbcClient jdbc;
    private final SpaceAccess access;
    private final AccountService accounts;
    private final CategoryService categories;
    private final TransactionService transactions;
    private final JsonMapper json;

    DataImportService(JdbcClient jdbc, SpaceAccess access, AccountService accounts, CategoryService categories,
                      TransactionService transactions, JsonMapper json) {
        this.jdbc = jdbc;
        this.access = access;
        this.accounts = accounts;
        this.categories = categories;
        this.transactions = transactions;
        this.json = json;
    }

    /** @param counts what came back, per section; @param skipped what couldn't, and why */
    public record Restored(Map<String, Integer> counts, List<String> skipped) {
    }

    @Transactional
    public Restored restore(CurrentUser user, byte[] file) {
        JsonNode root;
        try {
            root = json.readTree(file);
        } catch (Exception e) {
            throw new InvalidRequestException("import-unreadable", "that isn't a PayLedger export (a .json file from Settings > Account)");
        }
        boolean v1 = root.path("format").isMissingNode() && root.has("profile") && root.has("transactions");
        if (!v1 && (!DataFormat.NAME.equals(root.path("format").asString("")) || root.path("version").asInt(0) > DataFormat.VERSION)) {
            throw new InvalidRequestException("import-unreadable", "that isn't a PayLedger export, or it's from a newer version");
        }
        Space space = access.write(user, user.personalSpaceId());
        long existing = jdbc.sql("select count(*) from money_accounts where space_id = :s").param("s", space.id()).query(Long.class).single()
                + jdbc.sql("select count(*) from transactions where space_id = :s").param("s", space.id()).query(Long.class).single();
        if (existing > 0) {
            throw new InvalidRequestException("import-not-empty", "restore into an account with no accounts or entries yet, so nothing is doubled");
        }
        // Only the personal space's rows: a household in the file belongs to its other members too.
        String personal = personalSpace(root);
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        Map<String, UUID> ids = new HashMap<>();

        restoreProfile(user, root.path("profile"));
        counts.put("settings", 1);
        counts.put("categories", restoreCategories(space, rows(root, "categories", personal), ids));
        counts.put("accounts", restoreAccounts(space, user, rows(root, "accounts", personal), ids, skipped));
        counts.put("goals", restoreGoals(space, user, rows(root, "goals", personal), ids));
        counts.put("transactions", restoreTransactions(space, user, rows(root, "transactions", personal), ids, skipped));
        counts.put("budgets", restoreBudgets(space, user, rows(root, "budgets", personal), ids));
        counts.put("billsAndSavings", restoreCommitments(space, user, rows(root, "billsAndSavings", personal), ids));
        counts.put("billPayments", restorePayments(root.path("billPayments"), ids));
        counts.put("goalContributions", restoreContributions(user, root.path("goalContributions"), ids));
        counts.put("categoryRules", restoreRules(space, user, rows(root, "categoryRules", personal), ids));
        counts.put("taxMappings", restoreTax(space, rows(root, "taxMappings", personal), ids));
        counts.put("dailyServices", restoreDailyServices(space, user, rows(root, "dailyServices", personal), ids));
        counts.put("dailyServiceDays", restoreServiceDays(root.path("dailyServiceDays"), ids));
        counts.put("dailyServicePayments", restoreServicePayments(root.path("dailyServicePayments"), ids));
        if (root.path("groups").size() > 0) {
            skipped.add(root.path("groups").size() + " groups: shared with others, so they stay with them (ask to be added again)");
        }
        long households = stream(root.path("spaces")).filter(s -> "HOUSEHOLD".equals(s.path("kind").asString(""))).count();
        if (households > 0) {
            skipped.add(households + " households: shared with your family, so they're restored by their owner inviting you back");
        }
        return new Restored(counts, skipped);
    }

    // ---------------------------------------------------------------- sections

    private void restoreProfile(CurrentUser user, JsonNode profile) {
        JsonNode p = profile.isArray() ? profile.path(0) : profile;
        if (p.isMissingNode()) {
            return;
        }
        jdbc.sql("""
                        update users set locale = coalesce(:locale, locale), time_zone = coalesce(:tz, time_zone), currency = coalesce(:currency, currency),
                            budget_start_day = coalesce(:day, budget_start_day), upi_id = coalesce(:upi, upi_id),
                            preferences = coalesce(cast(:prefs as jsonb), preferences), updated_at = now()
                        where id = :u
                        """)
                .param("locale", text(p, "locale")).param("tz", text(p, "time_zone")).param("currency", text(p, "currency"))
                .param("day", p.path("budget_start_day").isNumber() ? p.path("budget_start_day").asInt() : null)
                .param("upi", text(p, "upi_id"))
                .param("prefs", p.path("preferences").isObject() ? p.path("preferences").toString() : null)
                .param("u", user.id()).update();
    }

    /** Default categories are matched by their seed; yours are made again, parents before children. */
    private int restoreCategories(Space space, List<JsonNode> rows, Map<String, UUID> ids) {
        categories.seedDefaults(space.id());
        int n = 0;
        List<JsonNode> ordered = new ArrayList<>(rows);
        ordered.sort((a, b) -> Boolean.compare(!a.path("parent_id").isNull() && !a.path("parent_id").isMissingNode(),
                !b.path("parent_id").isNull() && !b.path("parent_id").isMissingNode()));
        for (JsonNode c : ordered) {
            String seed = text(c, "seed_slug");
            UUID parent = ids.get(text(c, "parent_id"));
            UUID id = seed == null ? null : jdbc.sql("select id from categories where space_id = :s and seed_slug = :seed")
                    .param("s", space.id()).param("seed", seed).query(UUID.class).optional().orElse(null);
            if (id != null) {
                jdbc.sql("update categories set name = :name, icon = :icon, color = :color, hidden = :hidden, parent_id = :parent where id = :id")
                        .param("name", text(c, "name")).param("icon", text(c, "icon")).param("color", text(c, "color"))
                        .param("hidden", c.path("hidden").asBoolean(false)).param("parent", parent).param("id", id).update();
            } else {
                id = Uuid7.next();
                jdbc.sql("""
                                insert into categories (id, space_id, kind, name, icon, color, parent_id, sort_order, hidden)
                                values (:id, :s, :kind, :name, :icon, :color, :parent, :sort, :hidden)
                                """).param("id", id).param("s", space.id()).param("kind", text(c, "kind")).param("name", text(c, "name"))
                        .param("icon", text(c, "icon")).param("color", text(c, "color")).param("parent", parent)
                        .param("sort", c.path("sort_order").asInt(500)).param("hidden", c.path("hidden").asBoolean(false)).update();
            }
            ids.put(text(c, "id"), id);
            n++;
        }
        return n;
    }

    private int restoreAccounts(Space space, CurrentUser user, List<JsonNode> rows, Map<String, UUID> ids, List<String> skipped) {
        int n = 0;
        int owed = 0;
        for (JsonNode a : rows) {
            if ("OWED".equals(text(a, "kind"))) {
                // "Friends & groups" is the app's own, rebuilt from groups; groups don't come back.
                owed++;
                continue;
            }
            MoneyAccount made = accounts.restore(space, user, AccountKind.valueOf(text(a, "kind")), text(a, "name"), text(a, "currency"),
                    a.path("opening_balance").asLong(0), date(a, "opening_date"), !a.path("archived_at").isNull() && !a.path("archived_at").isMissingNode());
            ids.put(text(a, "id"), made.id());
            n++;
        }
        if (owed > 0) {
            skipped.add("\"Friends & groups\" and the group entries booked in it: they come from groups, which stay with the group");
        }
        return n;
    }

    private int restoreGoals(Space space, CurrentUser user, List<JsonNode> rows, Map<String, UUID> ids) {
        int n = 0;
        for (JsonNode g : rows) {
            UUID id = Uuid7.next();
            jdbc.sql("""
                            insert into goals (id, space_id, name, emoji, target, target_date, account_id, created_by, archived_at)
                            values (:id, :s, :name, :emoji, :target, :date, :account, :u, case when :archived then now() end)
                            """).param("id", id).param("s", space.id()).param("name", text(g, "name")).param("emoji", text(g, "emoji"))
                    .param("target", g.path("target").asLong()).param("date", date(g, "target_date")).param("account", ids.get(text(g, "account_id")))
                    .param("u", user.id()).param("archived", !g.path("archived_at").isNull() && !g.path("archived_at").isMissingNode()).update();
            ids.put(text(g, "id"), id);
            n++;
        }
        return n;
    }

    /** Through the ledger like any entry; group bookings are left out (their groups don't come back). */
    private int restoreTransactions(Space space, CurrentUser user, List<JsonNode> rows, Map<String, UUID> ids, List<String> skipped) {
        int n = 0;
        int missing = 0;
        for (JsonNode t : rows) {
            if (t.hasNonNull("group_link") || !t.path("deleted_at").isNull() && !t.path("deleted_at").isMissingNode()) {
                continue;
            }
            UUID from = ids.get(text(t, "account_id"));
            UUID to = ids.get(text(t, "to_account_id"));
            UUID category = ids.get(text(t, "category_id"));
            if (from == null || "TRANSFER".equals(text(t, "kind")) && to == null || !"TRANSFER".equals(text(t, "kind")) && category == null) {
                missing++;
                continue;
            }
            List<String> tags = new ArrayList<>();
            t.path("tags").forEach(x -> tags.add(x.asString()));
            UUID id = Uuid7.next();
            var fields = new TransactionRequests.Fields(text(t, "kind"), t.path("amount").asLong(), from, to, category, date(t, "occurred_on"),
                    text(t, "description"), text(t, "note"), tags, ids.get(text(t, "goal_id")), text(t, "counterparty"));
            transactions.createChecked(space, user, id, fields, accounts.find(space.id(), from, false),
                    to == null ? null : accounts.find(space.id(), to, false), category == null ? null : categories.find(space.id(), category), null, null);
            ids.put(text(t, "id"), id);
            n++;
        }
        if (missing > 0) {
            skipped.add(missing + " entries whose account or category wasn't in the file");
        }
        return n;
    }

    private int restoreBudgets(Space space, CurrentUser user, List<JsonNode> rows, Map<String, UUID> ids) {
        int n = 0;
        for (JsonNode b : rows) {
            UUID category = ids.get(text(b, "category_id"));
            if (category == null) {
                continue;
            }
            jdbc.sql("""
                            insert into budget_limits (space_id, category_id, effective_month, amount, currency, updated_by, rollover)
                            values (:s, :c, :month, :amount, :currency, :u, :rollover) on conflict do nothing
                            """).param("s", space.id()).param("c", category).param("month", date(b, "effective_month"))
                    .param("amount", b.path("amount").asLong()).param("currency", text(b, "currency")).param("u", user.id())
                    .param("rollover", b.path("rollover").asBoolean(false)).update();
            n++;
        }
        return n;
    }

    private int restoreCommitments(Space space, CurrentUser user, List<JsonNode> rows, Map<String, UUID> ids) {
        int n = 0;
        for (JsonNode c : rows) {
            UUID account = ids.get(text(c, "account_id"));
            if (account == null) {
                continue;
            }
            UUID id = Uuid7.next();
            jdbc.sql("""
                            insert into commitments (id, space_id, kind, name, amount, currency, account_id, category_id, to_account_id,
                                                     every_months, first_due, ends_on, created_by, goal_id, auto, auto_since)
                            values (:id, :s, :kind, :name, :amount, :currency, :account, :category, :to, :every, :first, :ends, :u, :goal, :auto, :since)
                            """).param("id", id).param("s", space.id()).param("kind", text(c, "kind")).param("name", text(c, "name"))
                    .param("amount", c.path("amount").asLong()).param("currency", text(c, "currency")).param("account", account)
                    .param("category", ids.get(text(c, "category_id"))).param("to", ids.get(text(c, "to_account_id")))
                    .param("every", c.path("every_months").asInt(1)).param("first", date(c, "first_due")).param("ends", date(c, "ends_on"))
                    .param("u", user.id()).param("goal", ids.get(text(c, "goal_id"))).param("auto", c.path("auto").asBoolean(false))
                    .param("since", date(c, "auto_since")).update();
            ids.put(text(c, "id"), id);
            n++;
        }
        return n;
    }

    private int restorePayments(JsonNode rows, Map<String, UUID> ids) {
        int n = 0;
        for (JsonNode p : rows) {
            UUID commitment = ids.get(text(p, "commitment_id"));
            UUID txn = ids.get(text(p, "transaction_id"));
            if (commitment == null || txn == null) {
                continue;
            }
            jdbc.sql("insert into commitment_payments (commitment_id, due_date, transaction_id) values (:c, :d, :t) on conflict do nothing")
                    .param("c", commitment).param("d", date(p, "due_date")).param("t", txn).update();
            n++;
        }
        return n;
    }

    private int restoreContributions(CurrentUser user, JsonNode rows, Map<String, UUID> ids) {
        int n = 0;
        for (JsonNode c : rows) {
            UUID goal = ids.get(text(c, "goal_id"));
            if (goal == null) {
                continue;
            }
            jdbc.sql("insert into goal_contributions (id, goal_id, amount, occurred_on, note, created_by) values (:id, :g, :a, :on, :note, :u)")
                    .param("id", Uuid7.next()).param("g", goal).param("a", c.path("amount").asLong()).param("on", date(c, "occurred_on"))
                    .param("note", text(c, "note")).param("u", user.id()).update();
            n++;
        }
        return n;
    }

    private int restoreRules(Space space, CurrentUser user, List<JsonNode> rows, Map<String, UUID> ids) {
        int n = 0;
        for (JsonNode r : rows) {
            UUID category = ids.get(text(r, "category_id"));
            if (category == null) {
                continue;
            }
            jdbc.sql("""
                            insert into category_rules (id, space_id, match, category_id, created_by, used) values (:id, :s, :m, :c, :u, :used)
                            on conflict do nothing
                            """).param("id", Uuid7.next()).param("s", space.id()).param("m", text(r, "match")).param("c", category)
                    .param("u", user.id()).param("used", r.path("used").asInt(0)).update();
            n++;
        }
        return n;
    }

    private int restoreTax(Space space, List<JsonNode> rows, Map<String, UUID> ids) {
        int n = 0;
        for (JsonNode t : rows) {
            UUID category = ids.get(text(t, "category_id"));
            if (category == null) {
                continue;
            }
            jdbc.sql("insert into tax_mappings (space_id, category_id, section) values (:s, :c, :section) on conflict do nothing")
                    .param("s", space.id()).param("c", category).param("section", text(t, "section")).update();
            n++;
        }
        return n;
    }

    private int restoreDailyServices(Space space, CurrentUser user, List<JsonNode> rows, Map<String, UUID> ids) {
        int n = 0;
        for (JsonNode d : rows) {
            UUID id = Uuid7.next();
            jdbc.sql("""
                            insert into daily_services (id, space_id, name, emoji, rate, per, paid_leaves, account_id, category_id, started_on, ended_on, created_by)
                            values (:id, :s, :name, :emoji, :rate, :per, :leaves, :account, :category, :start, :end, :u)
                            """).param("id", id).param("s", space.id()).param("name", text(d, "name")).param("emoji", text(d, "emoji"))
                    .param("rate", d.path("rate").asLong()).param("per", text(d, "per")).param("leaves", d.path("paid_leaves").asInt(0))
                    .param("account", ids.get(text(d, "account_id"))).param("category", ids.get(text(d, "category_id")))
                    .param("start", date(d, "started_on")).param("end", date(d, "ended_on")).param("u", user.id()).update();
            ids.put(text(d, "id"), id);
            n++;
        }
        return n;
    }

    private int restoreServiceDays(JsonNode rows, Map<String, UUID> ids) {
        int n = 0;
        for (JsonNode d : rows) {
            UUID service = ids.get(text(d, "service_id"));
            if (service != null) {
                jdbc.sql("insert into daily_service_days (service_id, day, quantity) values (:s, :d, :q) on conflict do nothing")
                        .param("s", service).param("d", date(d, "day")).param("q", d.path("quantity").asInt(1)).update();
                n++;
            }
        }
        return n;
    }

    private int restoreServicePayments(JsonNode rows, Map<String, UUID> ids) {
        int n = 0;
        for (JsonNode p : rows) {
            UUID service = ids.get(text(p, "service_id"));
            UUID txn = ids.get(text(p, "transaction_id"));
            if (service != null && txn != null) {
                jdbc.sql("insert into daily_service_payments (service_id, month, transaction_id, amount) values (:s, :m, :t, :a) on conflict do nothing")
                        .param("s", service).param("m", date(p, "month")).param("t", txn).param("a", p.path("amount").asLong()).update();
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- reading the file

    /** The file's personal space: by the version 2 field, else the PERSONAL row of "spaces". */
    private static String personalSpace(JsonNode root) {
        if (root.hasNonNull("personalSpaceId")) {
            return root.path("personalSpaceId").asString();
        }
        return stream(root.path("spaces")).filter(s -> "PERSONAL".equals(s.path("kind").asString(""))).map(s -> s.path("id").asString())
                .findFirst().orElse(null);
    }

    /** A section's rows from the personal space (rows without a space, like payments, are filtered by their links). */
    private static List<JsonNode> rows(JsonNode root, String section, String personal) {
        return stream(root.path(section)).filter(r -> personal == null || !r.has("space_id") || personal.equals(r.path("space_id").asString())).toList();
    }

    private static java.util.stream.Stream<JsonNode> stream(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(out::add);
        }
        return out.stream();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    private static LocalDate date(JsonNode n, String field) {
        String s = text(n, field);
        return s == null ? null : LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    }
}
