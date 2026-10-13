package dev.payledger.core.budget;

import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.common.NotFoundException;
import dev.payledger.core.ledger.LedgerClient;
import dev.payledger.core.space.SpaceAccess;
import dev.payledger.core.space.SpaceAccess.Space;
import dev.payledger.core.user.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Monthly spending limits per expense category, and how this month is going against them.
 * Spending comes from the ledger; a parent category's spending includes its subcategories'.
 */
@Service
public class BudgetService {

    /** "Close" from 80% of the limit, matching the alert the product plan sets at 80%. */
    static final int CLOSE_PERCENT = 80;
    /** The pace is a guess until a few days have gone by: shown from the 5th day (product plan). */
    static final int PACE_FROM_DAY = 5;
    private static final String CATEGORY = "category:";

    private final JdbcClient jdbc;
    private final SpaceAccess access;
    private final LedgerClient ledger;

    public BudgetService(JdbcClient jdbc, SpaceAccess access, LedgerClient ledger) {
        this.jdbc = jdbc;
        this.access = access;
        this.ledger = ledger;
    }

    public enum Status { ON_TRACK, CLOSE, OVER }

    /**
     * @param spent     null when the ledger can't be reached (the limits still show)
     * @param projected spending by the end of the period at the current pace; only for the current
     *                  period from its 5th day, null otherwise
     */
    public record Budget(UUID categoryId, long limit, String currency, Long spent, Long remaining, Status status, Long projected) {
    }

    /** @param today the user's date, so the client knows how far into the period it is */
    public record Month(YearMonth month, LocalDate start, LocalDate end, int days, int daysElapsed, LocalDate today,
                        boolean spentKnown, List<Budget> budgets) {
    }

    /** @param month null for the period that contains today */
    @Transactional(readOnly = true)
    public Month month(CurrentUser user, UUID spaceId, YearMonth month) {
        Space space = access.read(user, spaceId);
        Settings settings = settings(user);
        LocalDate today = settings.today();
        BudgetPeriod period = month != null ? BudgetPeriod.of(month, settings.startDay()) : BudgetPeriod.containing(today, settings.startDay());
        int elapsed = period.daysElapsed(today);

        List<Limit> limits = limits(spaceId, period.month());
        Optional<Map<UUID, Long>> spending = limits.isEmpty() ? Optional.of(Map.of()) : spending(space, period);

        List<Budget> budgets = limits.stream().map(l -> {
            Long spent = spending.map(s -> s.getOrDefault(l.categoryId(), 0L)).orElse(null);
            if (spent == null) {
                return new Budget(l.categoryId(), l.amount(), l.currency(), null, null, null, null);
            }
            return new Budget(l.categoryId(), l.amount(), l.currency(), spent, l.amount() - spent, status(spent, l.amount()),
                    projected(spent, period.days(), elapsed));
        }).toList();
        return new Month(period.month(), period.start(), period.end(), period.days(), elapsed, today, spending.isPresent(), budgets);
    }

    /** Straight-line: spending so far, spread over the whole period. Null before day 5 and once the period is over. */
    static Long projected(long spent, int days, int elapsed) {
        if (elapsed < PACE_FROM_DAY || elapsed >= days) {
            return null;
        }
        return Math.round((double) spent * days / elapsed);
    }

    static Status status(long spent, long limit) {
        if (spent > limit) {
            return Status.OVER;
        }
        return spent * 100 >= limit * CLOSE_PERCENT ? Status.CLOSE : Status.ON_TRACK;
    }

    /** Sets the limit from {@code from} (default: this period) onwards; later changes are replaced by it. */
    @Transactional
    public Month set(CurrentUser user, UUID spaceId, UUID categoryId, long amount, YearMonth from) {
        Space space = access.write(user, spaceId);
        String name = requireExpenseCategory(spaceId, categoryId);
        if (jdbc.sql("select hidden from categories where id = :id").param("id", categoryId).query(Boolean.class).single()) {
            throw new InvalidRequestException("budget-category-invalid", name + " is hidden; show it first");
        }
        YearMonth month = from != null ? from : currentMonth(user);
        write(space, user, categoryId, month, amount);
        return month(user, spaceId, month);
    }

    /** Ends the budget from {@code from} (default: this period) onwards; earlier months keep theirs. */
    @Transactional
    public void remove(CurrentUser user, UUID spaceId, UUID categoryId, YearMonth from) {
        Space space = access.write(user, spaceId);
        requireExpenseCategory(spaceId, categoryId);
        write(space, user, categoryId, from != null ? from : currentMonth(user), null);
    }

    private void write(Space space, CurrentUser user, UUID categoryId, YearMonth month, Long amount) {
        LocalDate effective = month.atDay(1);
        jdbc.sql("delete from budget_limits where category_id = :categoryId and effective_month > :month")
                .param("categoryId", categoryId).param("month", effective)
                .update();
        boolean limitedBefore = jdbc.sql("""
                        select coalesce((select amount is not null from budget_limits
                                         where category_id = :categoryId and effective_month < :month
                                         order by effective_month desc limit 1), false)
                        """)
                .param("categoryId", categoryId).param("month", effective)
                .query(Boolean.class).single();
        if (amount == null && !limitedBefore) {
            // Nothing to end: no row needed, and none left behind.
            jdbc.sql("delete from budget_limits where category_id = :categoryId and effective_month = :month")
                    .param("categoryId", categoryId).param("month", effective)
                    .update();
            return;
        }
        jdbc.sql("""
                        insert into budget_limits (space_id, category_id, effective_month, amount, currency, updated_by)
                        values (:spaceId, :categoryId, :month, :amount, :currency, :userId)
                        on conflict (category_id, effective_month)
                        do update set amount = excluded.amount, updated_by = excluded.updated_by, updated_at = now()
                        """)
                .param("spaceId", space.id()).param("categoryId", categoryId).param("month", effective)
                .param("amount", amount).param("currency", space.currency()).param("userId", user.id())
                .update();
    }

    private record Limit(UUID categoryId, long amount, String currency) {
    }

    /** Each category's latest row at or before the month, if it is a limit (not an end). */
    private List<Limit> limits(UUID spaceId, YearMonth month) {
        return jdbc.sql("""
                        select category_id, amount, currency from (
                            select distinct on (b.category_id) b.category_id, b.amount, b.currency, c.sort_order, c.name
                            from budget_limits b join categories c on c.id = b.category_id
                            where b.space_id = :spaceId and b.effective_month <= :month
                            order by b.category_id, b.effective_month desc
                        ) latest
                        where amount is not null
                        order by sort_order, name
                        """)
                .param("spaceId", spaceId).param("month", month.atDay(1))
                .query(Limit.class)
                .list();
    }

    /** Spending per category in the period, each subcategory's added to its parent's too. */
    private Optional<Map<UUID, Long>> spending(Space space, BudgetPeriod period) {
        return ledger.totals(space.ledgerBookRef(), "EXPENSE", period.start(), period.end(), false).map(totals -> {
            Map<UUID, UUID> parents = new HashMap<>();
            jdbc.sql("select id, parent_id from categories where space_id = :spaceId and parent_id is not null")
                    .param("spaceId", space.id())
                    .query((rs, n) -> parents.put(rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class)))
                    .list();
            Map<UUID, Long> spent = new HashMap<>();
            for (LedgerClient.PeriodTotal t : totals) {
                if (!t.accountRef().startsWith(CATEGORY) || !t.currency().equals(space.currency())) {
                    continue;
                }
                UUID id = UUID.fromString(t.accountRef().substring(CATEGORY.length()));
                spent.merge(id, t.total(), Long::sum);
                if (parents.containsKey(id)) {
                    spent.merge(parents.get(id), t.total(), Long::sum);
                }
            }
            return spent;
        });
    }

    private String requireExpenseCategory(UUID spaceId, UUID categoryId) {
        record Row(String name, String kind) {
        }
        Row row = jdbc.sql("select name, kind from categories where id = :id and space_id = :spaceId")
                .param("id", categoryId).param("spaceId", spaceId)
                .query(Row.class)
                .optional()
                .orElseThrow(() -> new NotFoundException("category"));
        if (!row.kind().equals("EXPENSE")) {
            throw new InvalidRequestException("budget-category-invalid", "budgets are for spending; " + row.name() + " is income");
        }
        return row.name();
    }

    private record Settings(int startDay, LocalDate today) {
    }

    /** The user's date and the budget period it falls in. */
    public record Now(LocalDate today, BudgetPeriod period) {
    }

    public Now now(CurrentUser user) {
        Settings s = settings(user);
        return new Now(s.today(), BudgetPeriod.containing(s.today(), s.startDay()));
    }

    /** The user's own start day and time zone (a household will have its own when households arrive in Phase 5). */
    private Settings settings(CurrentUser user) {
        record Row(int budgetStartDay, String timeZone) {
        }
        Row row = jdbc.sql("select budget_start_day, time_zone from users where id = :id")
                .param("id", user.id())
                .query(Row.class)
                .single();
        return new Settings(row.budgetStartDay(), LocalDate.now(ZoneId.of(row.timeZone())));
    }

    private YearMonth currentMonth(CurrentUser user) {
        Settings s = settings(user);
        return BudgetPeriod.containing(s.today(), s.startDay()).month();
    }
}
