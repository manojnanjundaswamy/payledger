package dev.payledger.core.user;

import dev.payledger.core.admin.IdentityAdmin;
import dev.payledger.core.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Your data, your control (DPDP Act 2023: the right to access and to erasure). Download everything PayLedger keeps
 * about you as one file, or delete your account: your own data goes, and so does your sign-in. In groups you share,
 * you become a named placeholder so the others' balances stay right; a group nobody else joined goes with you.
 * <p>
 * The ledger keeps its entries for your book (they carry no name or email); purging closed books is for later.
 */
@Service
public class DataRightsService {

    private final JdbcClient jdbc;
    private final IdentityAdmin identity;

    public DataRightsService(JdbcClient jdbc, IdentityAdmin identity) {
        this.jdbc = jdbc;
        this.identity = identity;
    }

    /** One section of the export: rows as JSON, straight from Postgres so every column is there. */
    private String rows(String sql, UUID userId) {
        return jdbc.sql("select coalesce(json_agg(r), '[]'::json)::text from (" + sql + ") r").param("u", userId).query(String.class).single();
    }

    private static final String MY_SPACES = "select space_id from space_members where user_id = :u";
    private static final String MY_GROUPS = "select group_id from group_members where user_id = :u";

    /** Everything about you, as a JSON document (each section is raw JSON text, assembled by the caller). */
    @Transactional(readOnly = true)
    public Map<String, String> export(CurrentUser user) {
        UUID u = user.id();
        Map<String, String> out = new LinkedHashMap<>();
        out.put("profile", rows("select id, email, display_name, locale, time_zone, currency, budget_start_day, upi_id, preferences, email_prefs, created_at from users where id = :u", u));
        out.put("spaces", rows("select * from spaces where id in (" + MY_SPACES + ")", u));
        out.put("accounts", rows("select * from money_accounts where space_id in (" + MY_SPACES + ") order by created_at", u));
        out.put("categories", rows("select * from categories where space_id in (" + MY_SPACES + ") order by kind, name", u));
        out.put("transactions", rows("select * from transactions where space_id in (" + MY_SPACES + ") and deleted_at is null order by occurred_on, created_at", u));
        out.put("budgets", rows("select * from budget_limits where space_id in (" + MY_SPACES + ") order by effective_month", u));
        out.put("billsAndSavings", rows("select * from commitments where space_id in (" + MY_SPACES + ") order by created_at", u));
        out.put("billPayments", rows("select p.* from commitment_payments p join commitments c on c.id = p.commitment_id where c.space_id in (" + MY_SPACES + ")", u));
        out.put("goals", rows("select * from goals where space_id in (" + MY_SPACES + ") order by created_at", u));
        out.put("capturedItems", rows("select id, source, status, sender, subject, body, kind, amount, occurred_on, merchant, bank, received_at from captured_items where user_id = :u order by received_at", u));
        out.put("goalContributions", rows("select c.* from goal_contributions c join goals g on g.id = c.goal_id where g.space_id in (" + MY_SPACES + ")", u));
        out.put("categoryRules", rows("select * from category_rules where space_id in (" + MY_SPACES + ") order by match", u));
        out.put("taxMappings", rows("select * from tax_mappings where space_id in (" + MY_SPACES + ")", u));
        out.put("imports", rows("select * from imports where space_id in (" + MY_SPACES + ") order by created_at", u));
        out.put("groups", rows("select * from groups where id in (" + MY_GROUPS + ")", u));
        out.put("groupMembers", rows("select id, group_id, display_name, role, user_id = :u as you, left_at from group_members where group_id in (" + MY_GROUPS + ")", u));
        out.put("groupExpenses", rows("select * from group_expenses where group_id in (" + MY_GROUPS + ") and deleted_at is null order by occurred_on", u));
        out.put("groupExpenseParts", rows("select p.* from group_expense_parts p join group_expenses e on e.id = p.expense_id where e.group_id in (" + MY_GROUPS + ") and e.deleted_at is null", u));
        out.put("groupSettlements", rows("select * from group_settlements where group_id in (" + MY_GROUPS + ") and deleted_at is null order by occurred_on", u));
        out.put("notifications", rows("select kind, title, body, link, created_at, read_at from inbox where user_id = :u order by created_at", u));
        out.put("emailsSent", rows("select kind, to_email, subject, created_at, sent_at from email_outbox where user_id = :u order by created_at", u));
        out.put("activity", rows("select type, action, status, target, ip, user_agent, at from user_events where user_id = :u order by at", u));
        return out;
    }

    /**
     * Deletes the account. {@code confirm} must be the account's email, so it can't happen by accident. Refused for
     * the last staff owner, who would lock everyone out of the admin console.
     */
    @Transactional
    public void delete(CurrentUser user, String confirm) {
        if (confirm == null || !confirm.strip().equalsIgnoreCase(user.email())) {
            throw new Refused("confirm-email", "type your email address to confirm");
        }
        UUID u = user.id();
        boolean lastOwner = jdbc.sql("""
                        select exists (select 1 from staff_roles where user_id = :u and role = 'OWNER')
                           and not exists (select 1 from staff_roles where role = 'OWNER' and user_id <> :u)
                        """).param("u", u).query(Boolean.class).single();
        if (lastOwner) {
            throw new Refused("last-owner", "you're the only owner of the admin console: make someone else an owner first");
        }

        // Groups: one only you joined goes; elsewhere you become a placeholder under your name, and what you created
        // is credited to another member who joined.
        List<UUID> groups = jdbc.sql("select group_id from group_members where user_id = :u").param("u", u).query(UUID.class).list();
        for (UUID g : groups) {
            UUID successor = jdbc.sql("select user_id from group_members where group_id = :g and user_id is not null and user_id <> :u order by created_at limit 1")
                    .param("g", g).param("u", u).query(UUID.class).optional().orElse(null);
            if (successor == null) {
                jdbc.sql("delete from group_expense_parts where expense_id in (select id from group_expenses where group_id = :g)").param("g", g).update();
                for (String table : List.of("group_events", "group_settlements", "group_expenses", "group_links", "group_members")) {
                    jdbc.sql("delete from " + table + " where group_id = :g").param("g", g).update();
                }
                jdbc.sql("delete from groups where id = :g").param("g", g).update();
            } else {
                for (String table : List.of("group_expenses", "group_settlements", "group_links")) {
                    jdbc.sql("update " + table + " set created_by = :s where group_id = :g and created_by = :u").param("s", successor).param("g", g).param("u", u).update();
                }
                jdbc.sql("update group_members set added_by = :s where group_id = :g and added_by = :u").param("s", successor).param("g", g).param("u", u).update();
                jdbc.sql("update groups set created_by = :s where id = :g and created_by = :u").param("s", successor).param("g", g).param("u", u).update();
                jdbc.sql("update group_members set user_id = null, email = null where group_id = :g and user_id = :u").param("g", g).param("u", u).update();
            }
        }

        // Auto-capture: your address and anything waiting to be reviewed.
        jdbc.sql("delete from captured_items where user_id = :u").param("u", u).update();
        jdbc.sql("delete from capture_addresses where user_id = :u").param("u", u).update();
        jdbc.sql("delete from capture_patterns where user_id = :u").param("u", u).update();
        jdbc.sql("delete from capture_aliases where user_id = :u").param("u", u).update();

        // Group bookings: which of your accounts you paid from.
        jdbc.sql("delete from group_entry_accounts where user_id = :u").param("u", u).update();
        jdbc.sql("update users set group_account_id = null where id = :u").param("u", u).update();

        // Households you share with others stay theirs: one you own goes to a partner (else the longest-standing
        // member), and what you recorded in any shared household is kept under its owner's name.
        List<UUID> owned = jdbc.sql("""
                        select s.id from spaces s where s.created_by = :u and s.kind = 'HOUSEHOLD'
                          and exists (select 1 from space_members m where m.space_id = s.id and m.user_id <> :u)
                        """).param("u", u).query(UUID.class).list();
        for (UUID h : owned) {
            UUID successor = jdbc.sql("""
                            select user_id from space_members where space_id = :h and user_id <> :u
                            order by role = 'PARTNER' desc, role = 'MEMBER' desc, joined_at limit 1
                            """).param("h", h).param("u", u).query(UUID.class).single();
            jdbc.sql("update spaces set created_by = :s where id = :h").param("s", successor).param("h", h).update();
            jdbc.sql("update space_members set role = 'OWNER' where space_id = :h and user_id = :s").param("h", h).param("s", successor).update();
        }
        List<UUID> shared = jdbc.sql("""
                        select s.id from space_members m join spaces s on s.id = m.space_id
                        where m.user_id = :u and s.kind = 'HOUSEHOLD' and s.created_by <> :u
                        """).param("u", u).query(UUID.class).list();
        for (UUID h : shared) {
            for (String table : List.of("money_accounts", "transactions", "commitments", "imports", "goals", "category_rules")) {
                jdbc.sql("update " + table + " set created_by = (select created_by from spaces where id = :h) where space_id = :h and created_by = :u")
                        .param("h", h).param("u", u).update();
            }
            jdbc.sql("update goal_contributions set created_by = (select created_by from spaces where id = :h) where created_by = :u and goal_id in (select id from goals where space_id = :h)")
                    .param("h", h).param("u", u).update();
            jdbc.sql("update budget_limits set updated_by = (select created_by from spaces where id = :h) where space_id = :h and updated_by = :u")
                    .param("h", h).param("u", u).update();
        }
        jdbc.sql("update space_invites set invited_by = (select created_by from spaces where id = space_invites.space_id) where invited_by = :u")
                .param("u", u).update();

        // Your own spaces and everything in them.
        List<UUID> spaces = jdbc.sql("select id from spaces where created_by = :u").param("u", u).query(UUID.class).list();
        for (UUID s : spaces) {
            jdbc.sql("delete from commitment_payments where commitment_id in (select id from commitments where space_id = :s)").param("s", s).update();
            jdbc.sql("delete from goal_contributions where goal_id in (select id from goals where space_id = :s)").param("s", s).update();
            jdbc.sql("delete from category_rules where space_id = :s").param("s", s).update();
            jdbc.sql("delete from tax_mappings where space_id = :s").param("s", s).update();
            jdbc.sql("update transactions set goal_id = null where space_id = :s").param("s", s).update();
            jdbc.sql("update commitments set goal_id = null where space_id = :s").param("s", s).update();
            for (String table : List.of("commitments", "goals", "transactions", "budget_limits", "imports", "money_accounts")) {
                jdbc.sql("delete from " + table + " where space_id = :s").param("s", s).update();
            }
            jdbc.sql("update categories set parent_id = null where space_id = :s").param("s", s).update();
            jdbc.sql("delete from categories where space_id = :s").param("s", s).update();
            jdbc.sql("delete from space_invites where space_id = :s").param("s", s).update();
            jdbc.sql("delete from space_members where space_id = :s").param("s", s).update();
            jdbc.sql("delete from outbox_events where aggregate_id = :s and published_at is not null").param("s", s).update();
            jdbc.sql("delete from spaces where id = :s").param("s", s).update();
        }

        // Your traces elsewhere.
        jdbc.sql("delete from space_members where user_id = :u").param("u", u).update();
        // Groups' histories keep the name they knew you by, not the account.
        jdbc.sql("update group_events set actor_user_id = null where actor_user_id = :u").param("u", u).update();
        jdbc.sql("delete from inbox where user_id = :u").param("u", u).update();
        jdbc.sql("update inbox set actor_id = null where actor_id = :u").param("u", u).update();
        jdbc.sql("delete from email_outbox where user_id = :u").param("u", u).update();
        jdbc.sql("delete from user_events where user_id = :u").param("u", u).update();
        jdbc.sql("delete from user_activity_days where user_id = :u").param("u", u).update();
        jdbc.sql("update request_log set user_id = null where user_id = :u").param("u", u).update();
        jdbc.sql("update admin_audit set target_user_id = null where target_user_id = :u").param("u", u).update();
        jdbc.sql("delete from admin_audit where actor_id = :u").param("u", u).update();
        jdbc.sql("delete from staff_roles where user_id = :u").param("u", u).update();
        jdbc.sql("update staff_roles set granted_by = null where granted_by = :u").param("u", u).update();
        jdbc.sql("update app_settings set updated_by = null where updated_by = :u").param("u", u).update();
        jdbc.sql("delete from users where id = :u").param("u", u).update();

        // Last: if the sign-in can't be removed, nothing above is kept either.
        identity.delete(user.subject());
    }

    static class Refused extends ApiException {
        Refused(String code, String message) {
            super(HttpStatus.CONFLICT, code, message);
        }
    }

    /** When the export was made, for its file name. */
    public static String stamp() {
        return Instant.now().toString().substring(0, 10);
    }
}
