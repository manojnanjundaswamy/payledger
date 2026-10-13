package dev.payledger.core.budget;

import dev.payledger.core.plan.PlanService;
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
    private final PlanService plans;
    private final dev.payledger.core.money.BalanceService balances;

    public BudgetService(JdbcClient jdbc, SpaceAccess access, LedgerClient ledger, PlanService plans,
                         dev.payledger.core.money.BalanceService balances) {
        this.jdbc = jdbc;
        this.access = access;
        this.ledger = ledger;
        this.plans = plans;
        this.balances = balances;
    }

    public enum Status { ON_TRACK, CLOSE, OVER }

    /**
     * @param rollover  unspent money carries into the next month (and an overspend comes off it)
     * @param carried   what earlier months carried into this one (rollover only; null if unknown or off)
     * @param available the limit plus what was carried: what this month can spend
     * @param spent     null when the ledger can't be reached (the limits still show)
     * @param projected spending by the end of the period at the current pace; only for the current
     *                  period from its 5th day, null otherwise
     */
    public record Budget(UUID categoryId, long limit, boolean rollover, Long carried, Long available, String currency,
                         Long spent, Long remaining, Status status, Long projected) {
    }

    /**
     * @param today  the user's date, so the client knows how far into the period it is
     * @param income received this month (null if unknown): with the limits, what is left to give a job
     */
    public record Month(YearMonth month, LocalDate start, LocalDate end, int days, int daysElapsed, LocalDate today,
                        boolean spentKnown, Long income, List<Budget> budgets) {
    }

    /** Rollover looks back at most a year, so one request stays quick. */
    static final int ROLLOVER_MONTHS = 12;

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
        Map<UUID, Long> carried = carried(space, limits, period.month(), settings.startDay());

        List<Budget> budgets = limits.stream().map(l -> {
            Long carry = l.rollover() ? carried.get(l.categoryId()) : null;
            boolean carryKnown = !l.rollover() || carry != null;
            Long available = carryKnown ? l.amount() + (carry == null ? 0 : carry) : null;
            Long spent = spending.map(s -> s.getOrDefault(l.categoryId(), 0L)).orElse(null);
            if (spent == null || available == null) {
                return new Budget(l.categoryId(), l.amount(), l.rollover(), carry, available, l.currency(), spent, null, null, null);
            }
            return new Budget(l.categoryId(), l.amount(), l.rollover(), carry, available, l.currency(), spent, available - spent,
                    status(spent, available), projected(spent, period.days(), elapsed));
        }).toList();
        Long income = ledger.totals(space.ledgerBookRef(), "INCOME", period.start(), period.end(), false)
                .map(rows -> -rows.stream().filter(t -> t.accountRef().startsWith(CATEGORY) && t.currency().equals(space.currency()))
                        .mapToLong(LedgerClient.PeriodTotal::total).sum())
                .orElse(null);
        return new Month(period.month(), period.start(), period.end(), period.days(), elapsed, today, spending.isPresent(), income, budgets);
    }

    /**
     * What earlier months carried into {@code month} for each rollover budget: each month before it, back
     * to the first that had no rollover budget for the category (or a year), adds its limit less its
     * spending. A category is missing from the map when the ledger couldn't say.
     */
    private Map<UUID, Long> carried(Space space, List<Limit> current, YearMonth month, int startDay) {
        Map<UUID, Long> carried = new HashMap<>();
        java.util.Set<UUID> open = new java.util.HashSet<>();
        current.stream().filter(Limit::rollover).forEach(l -> {
            carried.put(l.categoryId(), 0L);
            open.add(l.categoryId());
        });
        for (int back = 1; back <= ROLLOVER_MONTHS && !open.isEmpty(); back++) {
            YearMonth earlier = month.minusMonths(back);
            Map<UUID, Limit> then = new HashMap<>();
            limits(space.id(), earlier).stream().filter(Limit::rollover).forEach(l -> then.put(l.categoryId(), l));
            open.removeIf(id -> !then.containsKey(id));
            if (open.isEmpty()) {
                break;
            }
            Optional<Map<UUID, Long>> spent = spending(space, BudgetPeriod.of(earlier, startDay));
            if (spent.isEmpty()) {
                open.forEach(carried::remove);
                break;
            }
            for (UUID id : open) {
                carried.merge(id, then.get(id).amount() - spent.get().getOrDefault(id, 0L), Long::sum);
            }
        }
        return carried;
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
    public Month set(CurrentUser user, UUID spaceId, UUID categoryId, long amount, Boolean rollover, YearMonth from) {
        Space space = access.write(user, spaceId);
        String name = requireExpenseCategory(spaceId, categoryId);
        if (jdbc.sql("select hidden from categories where id = :id").param("id", categoryId).query(Boolean.class).single()) {
            throw new InvalidRequestException("budget-category-invalid", name + " is hidden; show it first");
        }
        YearMonth month = from != null ? from : currentMonth(user);
        List<Limit> existing = limits(spaceId, month);
        if (existing.stream().noneMatch(l -> l.categoryId().equals(categoryId))) {
            plans.check(spaceId, PlanService.Limit.BUDGETS, () -> limits(spaceId, month).size());
        }
        // Rollover stays as it was unless it is sent.
        boolean carry = rollover != null ? rollover
                : existing.stream().filter(l -> l.categoryId().equals(categoryId)).findFirst().map(Limit::rollover).orElse(false);
        write(space, user, categoryId, month, amount, carry);
        return month(user, spaceId, month);
    }

    /** Ends the budget from {@code from} (default: this period) onwards; earlier months keep theirs. */
    @Transactional
    public void remove(CurrentUser user, UUID spaceId, UUID categoryId, YearMonth from) {
        Space space = access.write(user, spaceId);
        requireExpenseCategory(spaceId, categoryId);
        write(space, user, categoryId, from != null ? from : currentMonth(user), null, false);
    }

    private void write(Space space, CurrentUser user, UUID categoryId, YearMonth month, Long amount, boolean rollover) {
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
                        insert into budget_limits (space_id, category_id, effective_month, amount, currency, rollover, updated_by)
                        values (:spaceId, :categoryId, :month, :amount, :currency, :rollover, :userId)
                        on conflict (category_id, effective_month)
                        do update set amount = excluded.amount, rollover = excluded.rollover, updated_by = excluded.updated_by,
                                      updated_at = now()
                        """)
                .param("spaceId", space.id()).param("categoryId", categoryId).param("month", effective)
                .param("amount", amount).param("currency", space.currency()).param("rollover", rollover).param("userId", user.id())
                .update();
    }

    private record Limit(UUID categoryId, long amount, String currency, boolean rollover) {
    }

    /** Each category's latest row at or before the month, if it is a limit (not an end). */
    private List<Limit> limits(UUID spaceId, YearMonth month) {
        return jdbc.sql("""
                        select category_id, amount, currency, rollover from (
                            select distinct on (b.category_id) b.category_id, b.amount, b.currency, b.rollover, c.sort_order, c.name
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

    /**
     * @param average  what the category spent per budget month, on average over the months looked at
     * @param suggested the average rounded up to the next ₹500, a limit that is realistic from day one
     */
    public record Suggestion(UUID categoryId, long average, long suggested) {
    }

    /**
     * Budgets suggested from the last {@code months} complete budget months: each spending category's average,
     * rounded up to the next ₹500. Categories already budgeted, hidden ones and anything under ₹100 a month are
     * left out. Empty if the ledger can't be reached (suggestions are a nicety, not a page that should fail).
     */
    public List<Suggestion> suggestions(CurrentUser user, UUID spaceId, int months) {
        access.read(user, spaceId);
        Settings settings = settings(user);
        BudgetPeriod current = BudgetPeriod.containing(settings.today(), settings.startDay());
        BudgetPeriod first = BudgetPeriod.of(current.month().minusMonths(months), settings.startDay());
        LocalDate to = current.start().minusDays(1);
        java.util.Set<UUID> budgeted = new java.util.HashSet<>();
        limits(spaceId, current.month()).forEach(l -> budgeted.add(l.categoryId()));
        java.util.Set<UUID> visible = new java.util.HashSet<>(jdbc.sql(
                        "select id from categories where space_id = :spaceId and kind = 'EXPENSE' and not hidden")
                .param("spaceId", spaceId).query(UUID.class).list());
        List<dev.payledger.core.money.BalanceService.CategoryTotal> totals;
        try {
            totals = balances.categoryTotals(user, spaceId, "EXPENSE", first.start(), to, false);
        } catch (dev.payledger.core.common.ApiException e) {
            return List.of();
        }
        long step = 50_000; // ₹500
        return totals.stream()
                .filter(t -> visible.contains(t.categoryId()) && !budgeted.contains(t.categoryId()))
                .map(t -> {
                    long average = t.amount() / months;
                    return new Suggestion(t.categoryId(), average, ((average + step - 1) / step) * step);
                })
                .filter(x -> x.average() >= 10_000)
                .sorted(java.util.Comparator.comparingLong(Suggestion::average).reversed())
                .toList();
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
