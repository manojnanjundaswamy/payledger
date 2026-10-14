package dev.payledger.core.notify;

import dev.payledger.core.budget.BudgetService;
import dev.payledger.core.money.Category;
import dev.payledger.core.money.CategoryService;
import dev.payledger.core.money.CommitmentService;
import dev.payledger.core.user.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The morning check (8:00 in each user's own time zone would be ideal; for now 8:00 IST, India first): bills and savings
 * due in the next three days, and budgets that got close to or over their limit. One digest per user per day, at most
 * one alert per budget per month and status, so nobody is nagged.
 */
@Component
public class Reminders {

    static final int DAYS_AHEAD = 3;
    private static final Logger log = LoggerFactory.getLogger(Reminders.class);

    private final JdbcClient jdbc;
    private final Notifications notifications;
    private final Inbox inbox;
    private final CommitmentService commitments;
    private final BudgetService budgets;
    private final CategoryService categories;
    private final TransactionTemplate tx;

    Reminders(JdbcClient jdbc, Notifications notifications, Inbox inbox, CommitmentService commitments, BudgetService budgets,
              CategoryService categories, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.inbox = inbox;
        this.commitments = commitments;
        this.budgets = budgets;
        this.categories = categories;
        this.tx = tx;
    }

    public record Run(int users, int billEmails, int budgetEmails) {
    }

    @Scheduled(cron = "${payledger.reminders.cron:0 0 8 * * *}", zone = "Asia/Kolkata")
    void everyMorning() {
        Run run = runAll();
        log.info("Reminders: {} users checked, {} bill digests, {} budget alerts queued", run.users(), run.billEmails(), run.budgetEmails());
    }

    /** Everyone active in the last 60 days who isn't disabled. Safe to run again: dedupe keys stop repeats. */
    public Run runAll() {
        List<CurrentUser> users = jdbc.sql("""
                        select u.id, u.subject, u.email, u.display_name, s.id as personal_space_id
                        from users u join spaces s on s.created_by = u.id and s.kind = 'PERSONAL'
                        where u.disabled_at is null and (u.last_active_on is null or u.last_active_on > current_date - 60)
                        """)
                .query(CurrentUser.class).list();
        int bills = 0;
        int alerts = 0;
        for (CurrentUser u : users) {
            try {
                Integer b = tx.execute(s -> bills(u) ? 1 : 0);
                Integer a = tx.execute(s -> budgetAlerts(u));
                tx.executeWithoutResult(s -> loans(u));
                bills += b == null ? 0 : b;
                alerts += a == null ? 0 : a;
            } catch (RuntimeException e) {
                // One user's problem (the ledger down for a moment) never stops everyone else's reminders.
                log.warn("Reminders skipped for user {}: {}", u.id(), e.getMessage());
            }
        }
        return new Run(users.size(), bills, alerts);
    }

    private boolean bills(CurrentUser u) {
        LocalDate today = today(u);
        overdue(u, today);
        List<CommitmentService.Due> due = commitments.due(u.personalSpaceId(), today, today.plusDays(DAYS_AHEAD)).stream()
                .filter(d -> !d.paid()).toList();
        if (due.isEmpty()) {
            return false;
        }
        DateTimeFormatter day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
        List<String> lines = due.stream()
                .map(d -> d.name() + ": " + money(d.amount()) + (d.dueDate().equals(today) ? " today" : " on " + day.format(d.dueDate())))
                .toList();
        long total = due.stream().mapToLong(CommitmentService.Due::amount).sum();
        String subject = due.size() == 1 ? due.getFirst().name() + " is due " + (due.getFirst().dueDate().equals(today) ? "today" : "soon")
                : due.size() + " bills and savings due in the next " + DAYS_AHEAD + " days";
        return inbox.notify(u.id(), Inbox.Kind.BILL_DUE, subject, String.join(" · ", lines), "/plan", null, "bills:" + u.id() + ":" + today,
                Email.titled(subject)
                        .heading("Coming up: " + money(total))
                        .lines(lines)
                        .para("Keep the money in the account it's paid from. When it's paid, mark it in your plan.")
                        .button("Open your plan", notifications.url("/plan"))
                        .footer("You got this because you have bills on your PayLedger plan.", notifications.settingsUrl()));
    }

    /** Due in the last week and still not marked paid: once per bill and date. */
    private void overdue(CurrentUser u, LocalDate today) {
        DateTimeFormatter day = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
        commitments.due(u.personalSpaceId(), today.minusDays(7), today.minusDays(1)).stream().filter(d -> !d.paid()).forEach(d ->
                inbox.notify(u.id(), Inbox.Kind.BILL_OVERDUE, d.name() + " was due on " + day.format(d.dueDate()),
                        money(d.amount()) + " isn't marked paid yet. Paid it? Mark it, so safe to spend stays right.", "/plan?open=" + d.commitmentId(),
                        null, "overdue:" + d.commitmentId() + ":" + d.dueDate(), null));
    }

    /** A loan due today, or three days ago and still not paid back: to whoever lent and whoever borrowed. */
    private void loans(CurrentUser u) {
        LocalDate today = today(u);
        record Due(java.util.UUID id, java.util.UUID groupId, String description, long amount, LocalDate dueOn, boolean lent, String other,
                   long owed) {
        }
        List<Due> due = jdbc.sql("""
                        select e.id, e.group_id, e.description, e.amount, e.due_on, mine.paid > 0 as lent,
                               (select o.display_name from group_members o where o.group_id = e.group_id and o.id <> me.id limit 1) as other,
                               -- What the borrower still owes the group (positive: owes).
                               coalesce((select sum(p.share - p.paid) from group_expense_parts p join group_expenses x on x.id = p.expense_id and x.deleted_at is null
                                         where p.member_id = b.member_id), 0)
                                 - coalesce((select sum(s.amount) from group_settlements s where s.from_member = b.member_id and s.deleted_at is null), 0)
                                 + coalesce((select sum(s.amount) from group_settlements s where s.to_member = b.member_id and s.deleted_at is null), 0) as owed
                        from group_expenses e
                        join group_members me on me.group_id = e.group_id and me.user_id = :u and me.left_at is null
                        join group_expense_parts mine on mine.expense_id = e.id and mine.member_id = me.id
                        join lateral (select member_id from group_expense_parts where expense_id = e.id and share > 0 limit 1) b on true
                        where e.loan and e.deleted_at is null and e.due_on in (:today, :late)
                        """).param("u", u.id()).param("today", today).param("late", today.minusDays(3)).query(Due.class).list();
        for (Due d : due) {
            if (d.owed() <= 0) {
                continue;
            }
            boolean late = !d.dueOn().equals(today);
            long amount = Math.min(d.amount(), d.owed());
            String title = d.lent()
                    ? d.other() + (late ? " hasn't paid back " : " should pay back ") + money(amount) + (late ? " yet" : " today")
                    : "Pay " + d.other() + " back " + money(amount) + (late ? ": it was due on " + d.dueOn().format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)) : " today");
            inbox.notify(u.id(), Inbox.Kind.LOAN_DUE, title, d.description(), "/people", null, "loan:" + d.id() + ":" + u.id() + ":" + d.dueOn() + ":" + late,
                    Email.titled(title).heading(title).para(d.description())
                            .button("Open People", notifications.url("/people"))
                            .footer("You got this because a loan between you has a date to pay back.", notifications.settingsUrl()));
        }
    }

    private int budgetAlerts(CurrentUser u) {
        BudgetService.Month month = budgets.month(u, u.personalSpaceId(), null);
        if (!month.spentKnown()) {
            return 0;
        }
        List<Category> all = categories.list(u, u.personalSpaceId());
        int sent = 0;
        for (BudgetService.Budget b : month.budgets()) {
            if (b.status() == null || b.status() == BudgetService.Status.ON_TRACK) {
                continue;
            }
            String name = all.stream().filter(c -> c.id().equals(b.categoryId())).map(Category::name).findFirst().orElse("A budget");
            boolean over = b.status() == BudgetService.Status.OVER;
            long available = b.available() != null ? b.available() : b.limit();
            Email email = Email.titled(over ? name + " is over budget" : name + " is close to its budget")
                    .heading(over ? name + ": " + money(b.spent() - available) + " over" : name + ": " + money(b.remaining()) + " left")
                    .para("Spent " + money(b.spent()) + " of " + money(available) + " this month (till " + month.end().format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)) + ").")
                    .button("See what it went on", notifications.url("/transactions?category=" + b.categoryId() + "&from=" + month.start() + "&to=" + month.end()))
                    .footer("You got this because you set a budget for " + name + ".", notifications.settingsUrl());
            String title = over ? name + " is over budget" : name + " is close to its budget";
            String link = "/transactions?category=" + b.categoryId() + "&from=" + month.start() + "&to=" + month.end();
            if (inbox.notify(u.id(), Inbox.Kind.BUDGET, title, "Spent " + money(b.spent()) + " of " + money(available) + " this month.", link,
                    null, "budget:" + u.id() + ":" + b.categoryId() + ":" + month.month() + ":" + b.status(), email)) {
                sent++;
            }
        }
        return sent;
    }

    private LocalDate today(CurrentUser u) {
        String zone = jdbc.sql("select time_zone from users where id = :id").param("id", u.id()).query(String.class).single();
        return LocalDate.now(ZoneId.of(zone));
    }

    /** ₹1,23,456 (whole rupees: an email is a nudge, not a statement). */
    public static String money(long paise) {
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"));
        f.setMaximumFractionDigits(0);
        return f.format(Math.round(paise / 100.0));
    }
}
