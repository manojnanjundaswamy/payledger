package dev.payledger.core.notify;

import dev.payledger.core.budget.BudgetService;
import dev.payledger.core.money.Category;
import dev.payledger.core.money.CategoryService;
import dev.payledger.core.money.CommitmentService;
import dev.payledger.core.money.GoalService;
import dev.payledger.core.user.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static dev.payledger.core.notify.Reminders.money;

/**
 * Monday's recap (docs/plan/ideas-2026-10.md, idea 7): last week's spending against the week before, where it went,
 * the bills due this week, budgets at risk and goals falling behind. The main reason people stop using a budget app
 * is forgetting it exists; one short email a week brings them back. In the app and by email (turn it off in
 * Settings → Notifications); one per user per week, and nothing for someone with nothing to report.
 */
@Component
public class WeeklyRecap {

    private static final Logger log = LoggerFactory.getLogger(WeeklyRecap.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final JdbcClient jdbc;
    private final Inbox inbox;
    private final Notifications notifications;
    private final CommitmentService commitments;
    private final BudgetService budgets;
    private final CategoryService categories;
    private final GoalService goals;
    private final TransactionTemplate tx;

    WeeklyRecap(JdbcClient jdbc, Inbox inbox, Notifications notifications, CommitmentService commitments, BudgetService budgets,
                CategoryService categories, GoalService goals, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.inbox = inbox;
        this.notifications = notifications;
        this.commitments = commitments;
        this.budgets = budgets;
        this.categories = categories;
        this.goals = goals;
        this.tx = tx;
    }

    public record Run(int users, int recaps) {
    }

    @Scheduled(cron = "${payledger.recap.cron:0 0 9 * * MON}", zone = "Asia/Kolkata")
    void everyMonday() {
        Run run = runAll();
        log.info("Weekly recap: {} users checked, {} recaps queued", run.users(), run.recaps());
    }

    /** Everyone active in the last 60 days who isn't disabled. Safe to run again: one recap per user per week. */
    public Run runAll() {
        List<CurrentUser> users = jdbc.sql("""
                        select u.id, u.subject, u.email, u.display_name, s.id as personal_space_id
                        from users u join spaces s on s.created_by = u.id and s.kind = 'PERSONAL'
                        where u.disabled_at is null and (u.last_active_on is null or u.last_active_on > current_date - 60)
                        """)
                .query(CurrentUser.class).list();
        int sent = 0;
        for (CurrentUser u : users) {
            try {
                String currency = jdbc.sql("select currency from spaces where id = :s").param("s", u.personalSpaceId()).query(String.class).optional().orElse("INR");
                Boolean done = Reminders.inCurrency(currency, () -> tx.execute(s -> recap(u, today(u))));
                sent += Boolean.TRUE.equals(done) ? 1 : 0;
            } catch (RuntimeException e) {
                log.warn("Weekly recap skipped for user {}: {}", u.id(), e.getMessage());
            }
        }
        return new Run(users.size(), sent);
    }

    /** The recap of the week (Monday to Sunday) before {@code today}; false when there was nothing to say or it went already. */
    public boolean recap(CurrentUser u, LocalDate today) {
        LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate from = thisMonday.minusWeeks(1);
        LocalDate to = thisMonday.minusDays(1);
        long spent = spent(u.personalSpaceId(), from, to);
        long before = spent(u.personalSpaceId(), from.minusWeeks(1), to.minusWeeks(1));
        long earned = jdbc.sql("""
                        select coalesce(sum(amount), 0) from transactions
                        where space_id = :s and deleted_at is null and kind = 'INCOME' and occurred_on between :from and :to
                        """).param("s", u.personalSpaceId()).param("from", from).param("to", to).query(Long.class).single();
        record Top(UUID categoryId, long amount) {
        }
        List<Top> top = jdbc.sql("""
                        select category_id, sum(amount) as amount from transactions
                        where space_id = :s and deleted_at is null and kind = 'EXPENSE' and occurred_on between :from and :to
                        group by category_id order by sum(amount) desc limit 3
                        """).param("s", u.personalSpaceId()).param("from", from).param("to", to).query(Top.class).list();
        List<CommitmentService.Due> due = commitments.due(u.personalSpaceId(), thisMonday, thisMonday.plusDays(6)).stream()
                .filter(d -> !d.paid()).toList();
        if (spent == 0 && earned == 0 && due.isEmpty()) {
            return false;
        }
        List<Category> all = categories.list(u, u.personalSpaceId());
        List<String> lines = new ArrayList<>();
        for (Top t : top) {
            String category = all.stream().filter(c -> c.id().equals(t.categoryId())).map(Category::name).findFirst().orElse("Other");
            lines.add(category + ": " + money(t.amount()));
        }
        long dueTotal = due.stream().mapToLong(CommitmentService.Due::amount).sum();
        if (!due.isEmpty()) {
            lines.add("Due this week: " + due.size() + (due.size() == 1 ? " bill or saving, " : " bills and savings, ") + money(dueTotal));
        }
        BudgetService.Month month = budgets.month(u, u.personalSpaceId(), null);
        if (month.spentKnown()) {
            long atRisk = month.budgets().stream().filter(b -> b.status() != null && b.status() != BudgetService.Status.ON_TRACK).count();
            if (atRisk > 0) {
                lines.add(atRisk + (atRisk == 1 ? " budget is" : " budgets are") + " close to or over the limit");
            }
        }
        long behind = goals.list(u, u.personalSpaceId(), false).stream().filter(g -> "BEHIND".equals(g.status())).count();
        if (behind > 0) {
            lines.add(behind + (behind == 1 ? " goal is" : " goals are") + " behind: a top-up keeps it on track");
        }

        String change = before == 0 ? "" : spent > before
                ? ", " + money(spent - before) + " more than the week before"
                : spent < before ? ", " + money(before - spent) + " less than the week before" : ", the same as the week before";
        String title = "Your week: " + money(spent) + " spent";
        String body = DAY.format(from) + " – " + DAY.format(to) + change + "." + (earned > 0 ? " " + money(earned) + " came in." : "");
        String link = "/reports?period=custom&from=" + from + "&to=" + to;
        return inbox.notify(u.id(), Inbox.Kind.RECAP, title, body, link, null, "recap:" + u.id() + ":" + from,
                Email.titled(title)
                        .heading(money(spent) + " spent last week")
                        .para(body)
                        .lines(lines)
                        .button("See last week", notifications.url(link))
                        .footer("You got this because the weekly recap is on.", notifications.settingsUrl()));
    }

    private long spent(UUID space, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        select coalesce(sum(amount), 0) from transactions
                        where space_id = :s and deleted_at is null and kind = 'EXPENSE' and occurred_on between :from and :to
                        """).param("s", space).param("from", from).param("to", to).query(Long.class).single();
    }

    private LocalDate today(CurrentUser u) {
        String zone = jdbc.sql("select time_zone from users where id = :id").param("id", u.id()).query(String.class).single();
        return LocalDate.now(ZoneId.of(zone));
    }
}
