package dev.payledger.core.budget;

import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.money.AccountKind;
import dev.payledger.core.money.AccountView;
import dev.payledger.core.money.BalanceService;
import dev.payledger.core.space.SpaceAccess;
import dev.payledger.core.user.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Helping people decide, not just record (product plan 1, "How we help people decide better"): a health score made of
 * a few plain checks, and "Can I afford it?" for a one-off cost or a monthly one (an EMI, a subscription). Both work
 * from what the app already knows: the last three full months of income and spending, the money in accounts, card
 * dues, budgets and this month's safe to spend. Nothing is stored.
 */
@Service
public class InsightsService {

    /** Months of history the averages use. */
    static final int MONTHS = 3;

    private final JdbcClient jdbc;
    private final SpaceAccess access;
    private final BalanceService balances;
    private final BudgetService budgets;
    private final SafeToSpendService safeToSpend;

    InsightsService(JdbcClient jdbc, SpaceAccess access, BalanceService balances, BudgetService budgets, SafeToSpendService safeToSpend) {
        this.jdbc = jdbc;
        this.access = access;
        this.balances = balances;
        this.budgets = budgets;
        this.safeToSpend = safeToSpend;
    }

    /**
     * @param income   average a month over the last full months (paise)
     * @param spent    average spending a month
     * @param months   how many full months had any entries (0: too new to judge)
     * @param cash     money in bank, cash and wallets now (null if the ledger couldn't be reached)
     * @param cardDues owed on cards now
     */
    public record Basis(long income, long spent, int months, Long cash, Long cardDues) {
        long surplus() {
            return income - spent;
        }
    }

    /** One check: points out of {@code max}, with what it means and what to do. */
    public record Part(String key, String title, int points, int max, String status, String detail, String tip) {
    }

    /** @param score 0–100, or null when there isn't a month of history yet */
    public record Health(Integer score, String grade, Basis basis, List<Part> parts) {
    }

    @Transactional(readOnly = true)
    public Health health(CurrentUser user, UUID spaceId) {
        return dev.payledger.core.notify.Reminders.inCurrency(currencyOf(spaceId), () -> healthIn(user, spaceId));
    }

    private Health healthIn(CurrentUser user, UUID spaceId) {
        Basis b = basis(user, spaceId);
        List<Part> parts = new ArrayList<>();
        if (b.months() == 0) {
            return new Health(null, "New", b, parts);
        }
        // 1. Saving: a fifth of income is the usual target.
        double rate = b.income() > 0 ? (double) b.surplus() / b.income() : -1;
        int saving = rate <= 0 ? 0 : (int) Math.round(Math.min(rate / 0.20, 1) * 30);
        parts.add(new Part("saving", "Saving", saving, 30, saving >= 24 ? "good" : saving >= 12 ? "fair" : "weak",
                b.income() > 0 ? "You keep " + Math.round(rate * 100) + "% of what comes in." : "No income recorded in the last months.",
                saving >= 24 ? "Keep it up; put the surplus to work in a goal." : "Aim to keep 20%: a SIP or a goal on payday makes it automatic."));
        // 2. Emergency fund: months of spending in the bank; six is comfortable.
        if (b.cash() != null && b.spent() > 0) {
            double cover = (double) b.cash() / b.spent();
            int fund = (int) Math.round(Math.min(cover / 6, 1) * 25);
            parts.add(new Part("cushion", "Emergency fund", fund, 25, cover >= 6 ? "good" : cover >= 3 ? "fair" : "weak",
                    String.format("Your bank, cash and wallets cover %.1f months of spending.", cover),
                    cover >= 6 ? "A solid cushion." : "Build it to 6 months of spending before big buys; a separate savings account helps."));
        }
        // 3. Cards: dues under a third of a month's income.
        if (b.cardDues() != null && b.income() > 0) {
            double ratio = (double) b.cardDues() / b.income();
            int cards = ratio <= 0.3 ? 15 : ratio >= 1 ? 0 : (int) Math.round((1 - (ratio - 0.3) / 0.7) * 15);
            parts.add(new Part("cards", "Card dues", cards, 15, ratio <= 0.3 ? "good" : ratio <= 0.6 ? "fair" : "weak",
                    "You owe " + Math.round(ratio * 100) + "% of a month's income on cards.",
                    ratio <= 0.3 ? "Pay in full each month and this stays green." : "Pay cards in full by the due date; interest is around 40% a year."));
        }
        // 4. Budgets kept last month.
        BudgetService.Month last = budgets.month(user, spaceId, YearMonth.from(budgets.now(user).period().start()).minusMonths(1));
        List<BudgetService.Budget> set = last.budgets().stream().filter(x -> x.spent() != null).toList();
        if (!set.isEmpty()) {
            long kept = set.stream().filter(x -> x.spent() <= (x.available() != null ? x.available() : x.limit())).count();
            int pts = (int) Math.round(15.0 * kept / set.size());
            parts.add(new Part("budgets", "Budgets kept", pts, 15, kept == set.size() ? "good" : kept * 2 >= set.size() ? "fair" : "weak",
                    kept + " of " + set.size() + " budgets stayed within their limit last month.",
                    kept == set.size() ? "Right on plan." : "Look at the ones that went over: raise the limit, or cut back there."));
        }
        // 5. Steady: spending no more than income in each of the months.
        int steady = steadyMonths(user, spaceId);
        parts.add(new Part("steady", "Living within income", Math.round(15f * steady / MONTHS), 15,
                steady == MONTHS ? "good" : steady >= 2 ? "fair" : "weak",
                "Income covered spending in " + steady + " of the last " + MONTHS + " months.",
                steady == MONTHS ? "Nicely balanced." : "A month that spends more than it earns eats savings; check the plan for big one-offs."));
        int max = parts.stream().mapToInt(Part::max).sum();
        int score = (int) Math.round(100.0 * parts.stream().mapToInt(Part::points).sum() / max);
        return new Health(score, score >= 80 ? "Great" : score >= 60 ? "Good" : score >= 40 ? "Fair" : "Needs care", b, parts);
    }

    /**
     * @param verdict YES, MAYBE or NO
     * @param reasons in plain words, the most important first
     */
    public record Afford(String verdict, String headline, List<String> reasons, Long safeToSpend, Basis basis) {
    }

    /**
     * @param amount  the cost (paise)
     * @param monthly true for a monthly cost (an EMI, a subscription), else a one-off
     */
    @Transactional(readOnly = true)
    public Afford afford(CurrentUser user, UUID spaceId, long amount, boolean monthly) {
        return dev.payledger.core.notify.Reminders.inCurrency(currencyOf(spaceId), () -> affordIn(user, spaceId, amount, monthly));
    }

    private Afford affordIn(CurrentUser user, UUID spaceId, long amount, boolean monthly) {
        if (amount <= 0) {
            throw new InvalidRequestException("invalid-amount", "enter how much it costs");
        }
        Basis b = basis(user, spaceId);
        Long safe = safeToSpend.current(user, spaceId).safeToSpend();
        List<String> reasons = new ArrayList<>();
        long surplus = b.surplus();
        if (monthly) {
            if (b.months() == 0) {
                return new Afford("MAYBE", "Not enough history yet", List.of("Add a month of income and spending, and this can tell you."), safe, b);
            }
            if (surplus <= 0) {
                reasons.add("You spend about as much as you earn, so a new monthly cost would come out of savings.");
                return new Afford("NO", "Not right now", reasons, safe, b);
            }
            long share = Math.round(100.0 * amount / surplus);
            reasons.add("It's " + share + "% of the " + rupees(surplus) + " you usually have left each month.");
            if (amount * 2 <= surplus) {
                reasons.add("You'd still keep " + rupees(surplus - amount) + " a month.");
                return new Afford("YES", "Yes, comfortably", reasons, safe, b);
            }
            if (amount <= surplus) {
                reasons.add("It fits, but leaves only " + rupees(surplus - amount) + " a month for savings and surprises.");
                return new Afford("MAYBE", "It fits, but it's tight", reasons, safe, b);
            }
            reasons.add("It's more than you usually have left, by " + rupees(amount - surplus) + " a month.");
            return new Afford("NO", "Not without cutting back", reasons, safe, b);
        }
        // A one-off: this month's safe to spend first, then savings above a 3-month cushion, then saving up for it.
        if (safe != null && amount <= safe) {
            reasons.add("This month you can still spend " + rupees(safe) + " after bills and savings; " + rupees(safe - amount) + " would be left.");
            return new Afford("YES", "Yes, from this month's money", reasons, safe, b);
        }
        if (safe != null) {
            reasons.add("It's more than this month's safe to spend (" + rupees(Math.max(safe, 0)) + ").");
        }
        long cushion = b.spent() * 3;
        if (b.cash() != null && b.cash() - amount >= cushion) {
            reasons.add("You could pay from savings and still keep 3 months of spending (" + rupees(cushion) + ") in the bank.");
            return new Afford("MAYBE", "Yes, from savings", reasons, safe, b);
        }
        if (surplus > 0) {
            long need = amount - Math.max(safe == null ? 0 : safe, 0);
            long months = (need + surplus - 1) / surplus;
            reasons.add("Saving what you usually have left (" + rupees(surplus) + " a month), you'd have it in about "
                    + months + (months == 1 ? " month" : " months") + ", by " + YearMonth.from(budgets.now(user).today()).plusMonths(months).getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + ".");
            reasons.add("A goal for it puts money aside each month.");
            return new Afford(months <= 3 ? "MAYBE" : "NO", months <= 3 ? "Soon, if you save for it" : "Not yet: save up first", reasons, safe, b);
        }
        reasons.add("Your spending is at or above your income, so there's nothing left over to save it from.");
        return new Afford("NO", "Not right now", reasons, safe, b);
    }

    // ---------------------------------------------------------------- the numbers

    Basis basis(CurrentUser user, UUID spaceId) {
        access.read(user, spaceId);
        LocalDate thisMonth = budgets.now(user).today().withDayOfMonth(1);
        LocalDate from = thisMonth.minusMonths(MONTHS);
        record Totals(long income, long spent, int months) {
        }
        Totals t = jdbc.sql("""
                        select coalesce(sum(amount) filter (where kind = 'INCOME'), 0) as income,
                               coalesce(sum(amount) filter (where kind = 'EXPENSE'), 0) as spent,
                               count(distinct date_trunc('month', occurred_on))::int as months
                        from transactions where space_id = :s and deleted_at is null and occurred_on >= :from and occurred_on < :to
                        """).param("s", spaceId).param("from", from).param("to", thisMonth).query(Totals.class).single();
        int months = Math.max(t.months(), 0);
        Long cash = null;
        Long cards = null;
        List<AccountView> accounts = balances.accounts(user, spaceId, false);
        if (accounts.stream().allMatch(a -> a.balance() != null)) {
            cash = accounts.stream().filter(a -> a.kind() == AccountKind.BANK || a.kind() == AccountKind.CASH || a.kind() == AccountKind.WALLET)
                    .mapToLong(AccountView::balance).sum();
            cards = accounts.stream().filter(a -> a.kind() == AccountKind.CARD).mapToLong(a -> Math.max(a.balance(), 0)).sum();
        }
        return months == 0 ? new Basis(0, 0, 0, cash, cards) : new Basis(t.income() / months, t.spent() / months, months, cash, cards);
    }

    private int steadyMonths(CurrentUser user, UUID spaceId) {
        LocalDate thisMonth = budgets.now(user).today().withDayOfMonth(1);
        return jdbc.sql("""
                        select count(*)::int from (
                          select date_trunc('month', occurred_on) m,
                                 sum(amount) filter (where kind = 'INCOME') i, sum(amount) filter (where kind = 'EXPENSE') e
                          from transactions where space_id = :s and deleted_at is null and occurred_on >= :from and occurred_on < :to
                          group by 1) x
                        where coalesce(i, 0) >= coalesce(e, 0)
                        """).param("s", spaceId).param("from", thisMonth.minusMonths(MONTHS)).param("to", thisMonth).query(Integer.class).single();
    }

    private String currencyOf(UUID spaceId) {
        return jdbc.sql("select currency from spaces where id = :s").param("s", spaceId).query(String.class).single();
    }

    private static String rupees(long paise) {
        return dev.payledger.core.notify.Reminders.money(paise);
    }
}
