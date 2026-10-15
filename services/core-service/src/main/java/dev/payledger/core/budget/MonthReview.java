package dev.payledger.core.budget;

import dev.payledger.core.notify.Inbox;
import dev.payledger.core.notify.Reminders;
import dev.payledger.core.space.SpaceAccess;
import dev.payledger.core.user.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A month looked back on (product plan, Phase 6 "monthly review"): what came in and went out against the month
 * before, the categories that moved, budgets kept, the biggest spends and the days with none, with a few highlights
 * in plain words. On the 1st, everyone who recorded something gets "Your September in review" in the bell.
 */
@Service
public class MonthReview {

    private static final Logger log = LoggerFactory.getLogger(MonthReview.class);

    private final JdbcClient jdbc;
    private final SpaceAccess access;
    private final BudgetService budgets;
    private final Inbox inbox;

    MonthReview(JdbcClient jdbc, SpaceAccess access, BudgetService budgets, Inbox inbox) {
        this.jdbc = jdbc;
        this.access = access;
        this.budgets = budgets;
        this.inbox = inbox;
    }

    /** A category's month and the month before (minor units). */
    public record Change(UUID categoryId, long amount, long before) {
    }

    public record Big(UUID id, String description, long amount, LocalDate occurredOn, UUID categoryId) {
    }

    /**
     * @param saved       income − spending (negative: more went out than came in)
     * @param noSpendDays days of the month (so far) with no spending
     * @param budgetsKept of {@code budgetsSet} budgets, how many stayed within their limit
     */
    public record Review(String month, String currency, long income, long spent, long saved, long incomeBefore, long spentBefore,
                         int entries, int noSpendDays, int days, List<Change> categories, List<Big> biggest, int budgetsSet,
                         int budgetsKept, List<String> highlights) {
    }

    record Totals(long income, long spent, int entries) {
    }

    /** @param month null for last month */
    @Transactional(readOnly = true)
    public Review review(CurrentUser user, UUID spaceId, YearMonth month) {
        SpaceAccess.Space space = access.read(user, spaceId);
        LocalDate today = budgets.now(user).today();
        YearMonth m = month != null ? month : YearMonth.from(today).minusMonths(1);
        LocalDate from = m.atDay(1);
        LocalDate to = m.atEndOfMonth();
        Totals now = totals(spaceId, from, to);
        Totals before = totals(spaceId, from.minusMonths(1), m.minusMonths(1).atEndOfMonth());
        List<Change> categories = jdbc.sql("""
                        select category_id,
                               coalesce(sum(amount) filter (where occurred_on >= :from), 0) as amount,
                               coalesce(sum(amount) filter (where occurred_on < :from), 0) as before
                        from transactions where space_id = :s and deleted_at is null and kind = 'EXPENSE'
                          and occurred_on between :prev and :to and category_id is not null
                        group by category_id order by 2 desc limit 8
                        """).param("s", spaceId).param("from", from).param("prev", from.minusMonths(1)).param("to", to)
                .query(Change.class).list().stream().filter(c -> c.amount() > 0).toList();
        List<Big> biggest = jdbc.sql("""
                        select id, description, amount, occurred_on, category_id from transactions
                        where space_id = :s and deleted_at is null and kind = 'EXPENSE' and occurred_on between :a and :b
                        order by amount desc limit 3
                        """).param("s", spaceId).param("a", from).param("b", to).query(Big.class).list();
        LocalDate last = to.isAfter(today) ? today : to;
        int days = last.isBefore(from) ? 0 : (int) (last.toEpochDay() - from.toEpochDay()) + 1;
        int spendDays = jdbc.sql("""
                        select count(distinct occurred_on)::int from transactions
                        where space_id = :s and deleted_at is null and kind = 'EXPENSE' and occurred_on between :a and :b
                        """).param("s", spaceId).param("a", from).param("b", last).query(Integer.class).single();
        List<BudgetService.Budget> set = budgets.month(user, spaceId, m).budgets().stream().filter(b -> b.spent() != null).toList();
        int kept = (int) set.stream().filter(b -> b.spent() <= (b.available() != null ? b.available() : b.limit())).count();
        long saved = now.income() - now.spent();
        List<String> highlights = Reminders.inCurrency(space.currency(), () -> highlights(now, before, saved, categories, set.size(), kept, days, spendDays));
        return new Review(m.toString(), space.currency(), now.income(), now.spent(), saved, before.income(), before.spent(), now.entries(),
                Math.max(days - spendDays, 0), days, categories, biggest, set.size(), kept, highlights);
    }

    private List<String> highlights(Totals now, Totals before, long saved, List<Change> categories, int budgetsSet, int kept, int days, int spendDays) {
        List<String> out = new ArrayList<>();
        if (now.income() > 0) {
            out.add(saved >= 0 ? "You kept " + Reminders.money(saved) + ", " + Math.round(100.0 * saved / now.income()) + "% of what came in."
                    : "You spent " + Reminders.money(-saved) + " more than came in.");
        }
        if (before.spent() > 0 && now.spent() > 0) {
            long diff = now.spent() - before.spent();
            long pct = Math.round(100.0 * Math.abs(diff) / before.spent());
            if (pct >= 5) {
                out.add("Spending was " + pct + "% " + (diff < 0 ? "lower" : "higher") + " than the month before.");
            }
        }
        categories.stream().filter(c -> c.before() > 0 && c.amount() > c.before()).max(Comparator.comparingLong(c -> c.amount() - c.before()))
                .ifPresent(c -> out.add("The biggest rise was " + categoryName(c.categoryId()) + ": up " + Reminders.money(c.amount() - c.before()) + "."));
        if (budgetsSet > 0) {
            out.add(kept == budgetsSet ? "Every budget stayed within its limit." : kept + " of " + budgetsSet + " budgets stayed within their limit.");
        }
        if (days > 0 && days - spendDays >= 3) {
            out.add((days - spendDays) + " days without spending anything.");
        }
        return out;
    }

    private Totals totals(UUID spaceId, LocalDate a, LocalDate b) {
        return jdbc.sql("""
                        select coalesce(sum(amount) filter (where kind = 'INCOME'), 0) as income,
                               coalesce(sum(amount) filter (where kind = 'EXPENSE'), 0) as spent, count(*)::int as entries
                        from transactions where space_id = :s and deleted_at is null and occurred_on between :a and :b
                        """).param("s", spaceId).param("a", a).param("b", b).query(Totals.class).single();
    }

    private String categoryName(UUID id) {
        return jdbc.sql("select name from categories where id = :id").param("id", id).query(String.class).optional().orElse("a category");
    }

    /** On the 1st, 9 a.m. India time: a notice to everyone who recorded something last month. Once each (dedupe). */
    @Scheduled(cron = "${payledger.review.cron:0 0 9 1 * *}", zone = "Asia/Kolkata")
    void onTheFirst() {
        log.info("Month in review: {} notices", announce(YearMonth.now(ZoneId.of("Asia/Kolkata")).minusMonths(1)));
    }

    int announce(YearMonth month) {
        String name = month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        int sent = 0;
        for (UUID user : jdbc.sql("""
                        select distinct s.created_by from transactions t join spaces s on s.id = t.space_id and s.kind = 'PERSONAL'
                        join users u on u.id = s.created_by
                        where t.deleted_at is null and t.occurred_on between :from and :to and u.disabled_at is null
                        """).param("from", month.atDay(1)).param("to", month.atEndOfMonth()).query(UUID.class).list()) {
            if (inbox.notify(user, Inbox.Kind.REVIEW, "Your " + name + " in review", "What came in, what went out, and what changed.",
                    "/review?month=" + month, null, "review:" + user + ":" + month, null)) {
                sent++;
            }
        }
        return sent;
    }
}
