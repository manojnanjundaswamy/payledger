package dev.payledger.core.budget;

import dev.payledger.core.money.Commitment;
import dev.payledger.core.money.CommitmentService;
import dev.payledger.core.space.SpaceAccess;
import dev.payledger.core.user.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The annual planner: big yearly and one-off costs (insurance, school fees, Diwali, a wedding) spread
 * into a monthly amount to set aside, so the month they are due isn't a shock.
 *
 * <p>A planned cost is a bill on the plan that comes every 6 or 12 months, or only once (its last
 * payment is its first). Each month before it is due, {@code amount / months left} is set aside; from the
 * month after it is due, the next one's set-aside starts. Safe to spend takes the set-asides off every
 * month, and in the month a cost is due treats it as already covered, so it doesn't count a second time.
 */
@Service
public class PlannerService {

    /** Half-yearly and yearly bills are "big"; monthly and quarterly ones are ordinary bills. */
    static final int PLANNED_FROM_MONTHS = 6;

    private final JdbcClient jdbc;
    private final SpaceAccess access;
    private final BudgetService budgets;
    private final CommitmentService commitments;

    public PlannerService(JdbcClient jdbc, SpaceAccess access, BudgetService budgets, CommitmentService commitments) {
        this.jdbc = jdbc;
        this.access = access;
        this.budgets = budgets;
        this.commitments = commitments;
    }

    /**
     * @param amount          for this month's date once paid, what was paid; otherwise the usual amount
     * @param monthsLeft      budget months before the one it is due in: 0 when it is due this month
     * @param paid            this month's date is paid (always false when monthsLeft > 0)
     * @param monthlySetAside what to put aside this month (0 in the month it is due)
     * @param covered         due this month and planned before this month began, so it was set aside for
     */
    public record PlannedCost(UUID commitmentId, String name, long amount, String currency, UUID categoryId,
                              int everyMonths, boolean oneOff, LocalDate nextDue, int monthsLeft, boolean paid,
                              long monthlySetAside, boolean covered) {
    }

    public record Planner(YearMonth month, long monthlySetAside, List<PlannedCost> items) {
    }

    @Transactional(readOnly = true)
    public Planner planner(CurrentUser user, UUID spaceId) {
        access.read(user, spaceId);
        return planner(spaceId, budgets.now(user).period());
    }

    /** For safe to spend, which already checked access. */
    Planner planner(UUID spaceId, BudgetPeriod period) {
        record Row(Commitment commitment, Instant createdAt) {
        }
        List<Row> rows = jdbc.sql("""
                        select id, kind, name, amount, currency, account_id, category_id, to_account_id, every_months,
                               first_due, ends_on, version, created_at
                        from commitments
                        where space_id = :spaceId and kind = 'BILL'
                          and (every_months >= :from or ends_on = first_due)
                          and (ends_on is null or ends_on >= :start)
                        """)
                .param("spaceId", spaceId).param("from", PLANNED_FROM_MONTHS).param("start", period.start())
                .query((rs, n) -> new Row(new Commitment(
                        rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("name"), rs.getLong("amount"),
                        rs.getString("currency"), rs.getObject("account_id", UUID.class), rs.getObject("category_id", UUID.class),
                        rs.getObject("to_account_id", UUID.class), rs.getInt("every_months"),
                        rs.getObject("first_due", LocalDate.class), rs.getObject("ends_on", LocalDate.class), rs.getInt("version")),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
        if (rows.isEmpty()) {
            return new Planner(period.month(), 0, List.of());
        }
        int startDay = period.start().getDayOfMonth();
        // Two years ahead covers the next date of anything yearly.
        List<CommitmentService.Due> due = commitments.due(spaceId, period.start(), period.start().plusYears(2).minusDays(1));
        Instant periodBegan = period.start().atStartOfDay().toInstant(ZoneOffset.UTC);

        List<PlannedCost> items = rows.stream().map(row -> {
            Commitment c = row.commitment();
            // This month's date (paid or not) is the one shown all month; after it, the next unpaid one.
            CommitmentService.Due next = due.stream()
                    .filter(d -> d.commitmentId().equals(c.id()) && (!d.paid() || !d.dueDate().isAfter(period.end())))
                    .findFirst().orElse(null);
            if (next == null) {
                return null;
            }
            YearMonth dueMonth = BudgetPeriod.containing(next.dueDate(), startDay).month();
            int monthsLeft = (int) ChronoUnit.MONTHS.between(period.month(), dueMonth);
            boolean oneOff = c.endsOn() != null && c.endsOn().equals(c.firstDue());
            return new PlannedCost(c.id(), c.name(), next.amount(), c.currency(), c.categoryId(), c.everyMonths(), oneOff,
                    next.dueDate(), monthsLeft, next.paid(), setAside(next.amount(), monthsLeft),
                    monthsLeft == 0 && row.createdAt().isBefore(periodBegan));
        }).filter(Objects::nonNull).sorted(Comparator.comparing(PlannedCost::nextDue)).toList();
        return new Planner(period.month(), items.stream().mapToLong(PlannedCost::monthlySetAside).sum(), items);
    }

    /** Spread evenly over the months left, rounded up so the last month isn't short. */
    static long setAside(long amount, int monthsLeft) {
        return monthsLeft <= 0 ? 0 : Math.ceilDiv(amount, monthsLeft);
    }
}
