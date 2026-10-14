package dev.payledger.core.money;

import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.common.NotFoundException;
import dev.payledger.core.common.Uuid7;
import dev.payledger.core.common.VersionConflictException;
import dev.payledger.core.plan.PlanService;
import dev.payledger.core.space.SpaceAccess;
import dev.payledger.core.user.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Savings goals: an amount to save, optionally by a date, with progress from a linked account's balance or from money
 * added by hand. Each goal says how much a month it still needs and whether it's on track (as far along as the time
 * gone by). Free spaces get a limited number; archived goals don't count.
 */
@Service
public class GoalService {

    private final JdbcClient jdbc;
    private final SpaceAccess access;
    private final BalanceService balances;
    private final PlanService plans;

    public GoalService(JdbcClient jdbc, SpaceAccess access, BalanceService balances, PlanService plans) {
        this.jdbc = jdbc;
        this.access = access;
        this.balances = balances;
        this.plans = plans;
    }

    /**
     * @param saved      paise towards the target: the linked account's balance, or the contributions; null when the
     *                   account's balance is unknown (the ledger couldn't be reached)
     * @param perMonth   what's still needed each month to reach the target by its date; null without a date or once reached
     * @param status     REACHED, ON_TRACK, BEHIND, or OPEN (no date)
     * @param behindBy   how far below the straight line from start to target date, when BEHIND
     */
    public record Goal(UUID id, String name, String emoji, long target, LocalDate targetDate, UUID accountId, int version,
                       LocalDate startedOn, boolean archived, Long saved, Long perMonth, String status, Long behindBy) {
    }

    public record Contribution(UUID id, long amount, LocalDate occurredOn, String note) {
    }

    public record Fields(String name, String emoji, long target, LocalDate targetDate, UUID accountId) {
    }

    record Row(UUID id, String name, String emoji, long target, LocalDate targetDate, UUID accountId, int version,
               java.time.OffsetDateTime createdAt, boolean archived, long contributed) {
    }

    private static final String SELECT = """
            select g.id, g.name, g.emoji, g.target, g.target_date, g.account_id, g.version, g.created_at, g.archived_at is not null as archived,
                   coalesce((select sum(c.amount) from goal_contributions c where c.goal_id = g.id), 0) as contributed
            from goals g
            """;

    @Transactional(readOnly = true)
    public List<Goal> list(CurrentUser user, UUID spaceId, boolean includeArchived) {
        access.read(user, spaceId);
        List<Row> rows = jdbc.sql(SELECT + " where g.space_id = :spaceId and (:all or g.archived_at is null) order by g.archived_at nulls first, g.target_date nulls last, g.created_at")
                .param("spaceId", spaceId).param("all", includeArchived).query(Row.class).list();
        Map<UUID, Long> accountBalances = rows.stream().anyMatch(r -> r.accountId() != null)
                ? balances.accounts(user, spaceId, true).stream().filter(a -> a.balance() != null)
                .collect(Collectors.toMap(AccountView::id, AccountView::balance))
                : Map.of();
        LocalDate today = LocalDate.now();
        return rows.stream().map(r -> view(r, r.accountId() == null ? Long.valueOf(r.contributed()) : accountBalances.get(r.accountId()), today)).toList();
    }

    @Transactional(readOnly = true)
    public List<Contribution> contributions(CurrentUser user, UUID spaceId, UUID goalId) {
        access.read(user, spaceId);
        find(spaceId, goalId);
        return jdbc.sql("select id, amount, occurred_on, note from goal_contributions where goal_id = :id order by occurred_on desc, created_at desc")
                .param("id", goalId).query(Contribution.class).list();
    }

    @Transactional
    public Goal create(CurrentUser user, UUID spaceId, Fields f) {
        access.write(user, spaceId);
        check(spaceId, f);
        plans.check(spaceId, PlanService.Limit.GOALS);
        UUID id = Uuid7.next();
        jdbc.sql("""
                        insert into goals (id, space_id, name, emoji, target, target_date, account_id, created_by)
                        values (:id, :spaceId, :name, :emoji, :target, :targetDate, :accountId, :userId)
                        """)
                .param("id", id).param("spaceId", spaceId).param("name", f.name().strip()).param("emoji", f.emoji())
                .param("target", f.target()).param("targetDate", f.targetDate()).param("accountId", f.accountId())
                .param("userId", user.id()).update();
        return one(user, spaceId, id);
    }

    @Transactional
    public Goal update(CurrentUser user, UUID spaceId, UUID id, int version, Fields f, Boolean archived) {
        access.write(user, spaceId);
        Row before = find(spaceId, id);
        if (before.version() != version) {
            throw new VersionConflictException("goal");
        }
        check(spaceId, f);
        if (Boolean.FALSE.equals(archived) && before.archived()) {
            plans.check(spaceId, PlanService.Limit.GOALS);
        }
        jdbc.sql("""
                        update goals set name = :name, emoji = :emoji, target = :target, target_date = :targetDate, account_id = :accountId,
                               archived_at = case when cast(:archived as boolean) is null then archived_at
                                                  when :archived then coalesce(archived_at, now()) else null end,
                               version = version + 1
                        where id = :id
                        """)
                .param("name", f.name().strip()).param("emoji", f.emoji()).param("target", f.target()).param("targetDate", f.targetDate())
                .param("accountId", f.accountId()).param("archived", archived).param("id", id).update();
        return one(user, spaceId, id);
    }

    @Transactional
    public Goal contribute(CurrentUser user, UUID spaceId, UUID id, long amount, LocalDate occurredOn, String note) {
        access.write(user, spaceId);
        Row goal = find(spaceId, id);
        if (goal.accountId() != null) {
            throw new InvalidRequestException("goal-uses-account", "this goal follows an account's balance; add money to that account instead");
        }
        if (amount == 0) {
            throw new InvalidRequestException("invalid-amount", "amount can't be zero");
        }
        if (goal.contributed() + amount < 0) {
            throw new InvalidRequestException("invalid-amount", "you can't take out more than was put in");
        }
        jdbc.sql("insert into goal_contributions (id, goal_id, amount, occurred_on, note, created_by) values (:id, :goalId, :amount, :on, :note, :userId)")
                .param("id", Uuid7.next()).param("goalId", id).param("amount", amount).param("on", occurredOn == null ? LocalDate.now() : occurredOn)
                .param("note", note == null || note.isBlank() ? null : note.strip()).param("userId", user.id()).update();
        return one(user, spaceId, id);
    }

    @Transactional
    public void deleteContribution(CurrentUser user, UUID spaceId, UUID goalId, UUID contributionId) {
        access.write(user, spaceId);
        find(spaceId, goalId);
        if (jdbc.sql("delete from goal_contributions where id = :id and goal_id = :goalId").param("id", contributionId).param("goalId", goalId).update() == 0) {
            throw new NotFoundException("contribution");
        }
    }

    /** Deleting a goal removes it and what was added to it (account balances are untouched). */
    @Transactional
    public void delete(CurrentUser user, UUID spaceId, UUID id) {
        access.write(user, spaceId);
        find(spaceId, id);
        jdbc.sql("delete from goal_contributions where goal_id = :id").param("id", id).update();
        jdbc.sql("delete from goals where id = :id").param("id", id).update();
    }

    private Goal one(CurrentUser user, UUID spaceId, UUID id) {
        return list(user, spaceId, true).stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow(() -> new NotFoundException("goal"));
    }

    private Row find(UUID spaceId, UUID id) {
        return jdbc.sql(SELECT + " where g.id = :id and g.space_id = :spaceId").param("id", id).param("spaceId", spaceId)
                .query(Row.class).optional().orElseThrow(() -> new NotFoundException("goal"));
    }

    private void check(UUID spaceId, Fields f) {
        if (f.name() == null || f.name().isBlank()) {
            throw new InvalidRequestException("invalid-goal", "give the goal a name");
        }
        if (f.targetDate() != null && f.targetDate().isBefore(LocalDate.now().minusYears(1))) {
            throw new InvalidRequestException("invalid-goal", "the date is too far in the past");
        }
        if (f.accountId() != null && !jdbc.sql("select exists (select 1 from money_accounts where id = :id and space_id = :spaceId)")
                .param("id", f.accountId()).param("spaceId", spaceId).query(Boolean.class).single()) {
            throw new NotFoundException("account");
        }
    }

    /** Progress, the monthly amount still needed, and whether it's on track: as far along as the time gone by. */
    static Goal view(Row r, Long saved, LocalDate today) {
        LocalDate start = r.createdAt().toLocalDate();
        Long perMonth = null;
        String status = "OPEN";
        Long behindBy = null;
        if (saved != null && saved >= r.target()) {
            status = "REACHED";
        } else if (r.targetDate() != null && saved != null) {
            long left = r.target() - saved;
            long months = Math.max(1, ChronoUnit.MONTHS.between(today.withDayOfMonth(1), r.targetDate().withDayOfMonth(1)) + (r.targetDate().isBefore(today) ? 0 : 1));
            perMonth = (left + months - 1) / months;
            long total = Math.max(1, ChronoUnit.DAYS.between(start, r.targetDate()));
            long gone = Math.min(total, Math.max(0, ChronoUnit.DAYS.between(start, today)));
            long expected = r.target() * gone / total;
            status = saved >= expected ? "ON_TRACK" : "BEHIND";
            behindBy = saved >= expected ? null : expected - saved;
        }
        return new Goal(r.id(), r.name(), r.emoji(), r.target(), r.targetDate(), r.accountId(), r.version(), start, r.archived(),
                saved, perMonth, status, behindBy);
    }
}
