package dev.payledger.core.money;

import java.util.List;
import java.util.Map;

import static java.util.Map.entry;

/**
 * The one definition of "your data" as a file: what the export writes, what the import reads back, and where every
 * table that holds a person's data goes. Export and import both work from these lists, and two tests keep them
 * honest: a round trip (export → import into a fresh account → export again, compared), and a schema check that fails
 * when a table holding someone's data appears without a decision here.
 */
public final class DataFormat {

    private DataFormat() {
    }

    public static final String NAME = "payledger-export";
    /** 1: the first export (no version field); 2: adds format, version, personal space and tax sections; 3: daily services. */
    public static final int VERSION = 3;

    /** Sections a restore brings back into your personal space, in the order they're restored. */
    public static final List<String> RESTORED = List.of("profile", "categories", "accounts", "goals", "transactions", "budgets",
            "billsAndSavings", "billPayments", "goalContributions", "categoryRules", "taxMappings", "dailyServices", "dailyServiceDays",
            "dailyServicePayments");

    /**
     * Every table with a person's data: the export section it's in, or (starting "-") why it isn't exported. A new
     * table that holds user, space or group data must be added here, or DataFormatTest fails.
     */
    public static final Map<String, String> TABLES = Map.ofEntries(
            entry("users", "profile"),
            entry("spaces", "spaces"),
            entry("space_members", "spaces"),
            entry("money_accounts", "accounts"),
            entry("categories", "categories"),
            entry("transactions", "transactions"),
            entry("budget_limits", "budgets"),
            entry("commitments", "billsAndSavings"),
            entry("commitment_payments", "billPayments"),
            entry("goals", "goals"),
            entry("goal_contributions", "goalContributions"),
            entry("category_rules", "categoryRules"),
            entry("tax_mappings", "taxMappings"),
            entry("imports", "imports"),
            entry("daily_services", "dailyServices"),
            entry("daily_service_days", "dailyServiceDays"),
            entry("daily_service_payments", "dailyServicePayments"),
            entry("captured_items", "capturedItems"),
            entry("groups", "groups"),
            entry("group_members", "groupMembers"),
            entry("group_expenses", "groupExpenses"),
            entry("group_expense_parts", "groupExpenseParts"),
            entry("group_settlements", "groupSettlements"),
            entry("group_comments", "groupComments"),
            entry("inbox", "notifications"),
            entry("email_outbox", "emailsSent"),
            entry("user_events", "activity"),
            entry("capture_addresses", "-a new forwarding address is made for the new account"),
            entry("capture_patterns", "-patterns learned from alerts hold their private text"),
            entry("capture_aliases", "-learned names follow the patterns"),
            entry("group_entry_accounts", "-which account paid a group entry: rebuilt by the group bookings"),
            entry("group_events", "-a group's history belongs to the group, not one member"),
            entry("group_links", "-join links are secrets of the group"),
            entry("space_invites", "-invite tokens are secrets"),
            entry("achievements", "-badges are earned again from the restored entries"),
            entry("inbound_emails", "emailsReceived"),
            entry("ai_requests", "aiRequests"),
            entry("push_subscriptions", "-push addresses belong to a device; turn notifications on again there"),
            entry("guest_codes", "-one-time sign-in codes for guests, used within minutes"),
            entry("guest_sessions", "-guest sign-in sessions are secrets"),
            entry("request_log", "-operational log, kept 90 days"),
            entry("user_activity_days", "-counts for the admin dashboard"),
            entry("admin_audit", "-staff actions, kept for accountability"),
            entry("staff_roles", "-staff access is granted, not exported"));
}
