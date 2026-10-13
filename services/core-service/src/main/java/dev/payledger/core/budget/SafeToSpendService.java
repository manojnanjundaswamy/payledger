package dev.payledger.core.budget;

import dev.payledger.core.ledger.LedgerClient;
import dev.payledger.core.money.CommitmentService;
import dev.payledger.core.space.SpaceAccess;
import dev.payledger.core.space.SpaceAccess.Space;
import dev.payledger.core.user.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * "Salary came on the 1st, broke by the 20th" (product plan): what is left to spend this budget month
 * once the bills still due and this month's savings are put aside.
 *
 * <pre>safe to spend = income this month - spent this month - bills still due this month - savings due this month</pre>
 *
 * A bill, once paid, is an expense and so already in "spent"; a saving is a transfer, never spending, so
 * it counts whether paid or not. Card bills aren't commitments here: what went on the card is already spent.
 */
@Service
public class SafeToSpendService {

    private final SpaceAccess access;
    private final BudgetService budgets;
    private final CommitmentService commitments;
    private final LedgerClient ledger;

    public SafeToSpendService(SpaceAccess access, BudgetService budgets, CommitmentService commitments, LedgerClient ledger) {
        this.access = access;
        this.budgets = budgets;
        this.commitments = commitments;
        this.ledger = ledger;
    }

    /**
     * @param known       false when the ledger can't be reached: income, spent and the result are then null
     * @param billsLeft   bills due this month and not paid yet (overdue ones included)
     * @param perDay      what is safe per day for the days left (today included), never below zero
     */
    public record SafeToSpend(YearMonth month, LocalDate start, LocalDate end, int daysLeft, String currency, boolean known,
                              Long income, Long spent, long billsLeft, long savings, Long safeToSpend, Long perDay) {
    }

    @Transactional(readOnly = true)
    public SafeToSpend current(CurrentUser user, UUID spaceId) {
        Space space = access.read(user, spaceId);
        BudgetService.Now now = budgets.now(user);
        BudgetPeriod period = now.period();
        int daysLeft = period.days() - period.daysElapsed(now.today()) + 1;

        List<CommitmentService.Due> due = commitments.due(spaceId, period.start(), period.end()).stream()
                .filter(d -> d.currency().equals(space.currency()))
                .toList();
        long billsLeft = due.stream().filter(d -> d.kind().equals("BILL") && !d.paid()).mapToLong(CommitmentService.Due::amount).sum();
        long savings = due.stream().filter(d -> d.kind().equals("SAVING")).mapToLong(CommitmentService.Due::amount).sum();

        Optional<Long> income = total(space, "INCOME", period);
        Optional<Long> spent = total(space, "EXPENSE", period);
        if (income.isEmpty() || spent.isEmpty()) {
            return new SafeToSpend(period.month(), period.start(), period.end(), daysLeft, space.currency(), false,
                    null, null, billsLeft, savings, null, null);
        }
        // Income is a credit (negative) in the ledger.
        long in = -income.get();
        long safe = in - spent.get() - billsLeft - savings;
        return new SafeToSpend(period.month(), period.start(), period.end(), daysLeft, space.currency(), true,
                in, spent.get(), billsLeft, savings, safe, Math.max(0, safe) / daysLeft);
    }

    private Optional<Long> total(Space space, String type, BudgetPeriod period) {
        return ledger.totals(space.ledgerBookRef(), type, period.start(), period.end(), false)
                .map(rows -> rows.stream()
                        .filter(t -> t.accountRef().startsWith("category:") && t.currency().equals(space.currency()))
                        .mapToLong(LedgerClient.PeriodTotal::total)
                        .sum());
    }
}
