package dev.payledger.core.progress;

import dev.payledger.core.budget.BudgetService;
import dev.payledger.core.budget.InsightsService;
import dev.payledger.core.money.GoalService;
import dev.payledger.core.notify.Inbox;
import dev.payledger.core.user.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Making good money habits feel good: a streak of days you recorded your money, badges for things that actually help
 * (a month on budget, a cushion, a goal reached; never for spending), points and a level, and one small challenge a
 * week. All worked out from what's already recorded; badges are kept once earned and announced in the bell.
 */
@Service
public class ProgressService {

    /** A badge: what it is, how to earn it, and how it's checked. */
    record Def(String key, String group, String title, String how, String icon, int points) {
    }

    static final List<Def> BADGES = List.of(
            new Def("first-entry", "Getting started", "First entry", "Record your first income or expense.", "pencil", 20),
            new Def("budget-set", "Getting started", "Budget set", "Set a monthly limit on a category.", "pie-chart", 30),
            new Def("bills-planned", "Getting started", "No surprises", "Add a bill or saving to your plan.", "calendar-clock", 30),
            new Def("goal-setter", "Getting started", "Goal setter", "Start a savings goal.", "goal", 30),
            new Def("streak-7", "Habits", "One week strong", "Record your money 7 days in a row.", "flame", 60),
            new Def("streak-30", "Habits", "Thirty-day habit", "Record your money 30 days in a row.", "flame", 150),
            new Def("no-spend-day", "Habits", "No-spend day", "Go a whole day without spending, after your first week.", "leaf", 40),
            new Def("auto-pilot", "Habits", "On auto-pilot", "Add an entry from auto-capture (SMS, email or a statement).", "inbox", 40),
            new Def("on-budget", "Discipline", "On budget", "Finish a month with every budget within its limit.", "check-circle", 120),
            new Def("saver-20", "Discipline", "Saver", "Keep 20% of what comes in, in a month.", "piggy-bank", 120),
            new Def("cushion-3", "Discipline", "Safety cushion", "Hold 3 months of spending in bank, cash and wallets.", "shield", 150),
            new Def("goal-reached", "Discipline", "Goal reached", "Fill a savings goal to its target.", "trophy", 150),
            new Def("splitter", "Together", "Fair share", "Split an expense in a group.", "split", 30),
            new Def("settled", "Together", "All square", "Record a settle-up in a group.", "hand-coins", 40),
            new Def("household", "Together", "Family plan", "Share a household with someone.", "home", 60));

    /** Levels by points: a name for each, so progress reads as growth, not a score. */
    static final List<Map.Entry<Integer, String>> LEVELS = List.of(
            Map.entry(0, "Seedling"), Map.entry(100, "Sprout"), Map.entry(250, "Grower"),
            Map.entry(500, "Planner"), Map.entry(900, "Money pro"), Map.entry(1400, "Money master"));

    private final JdbcClient jdbc;
    private final Inbox inbox;
    private final BudgetService budgets;
    private final InsightsService insights;
    private final GoalService goals;

    ProgressService(JdbcClient jdbc, Inbox inbox, BudgetService budgets, InsightsService insights, GoalService goals) {
        this.jdbc = jdbc;
        this.inbox = inbox;
        this.budgets = budgets;
        this.insights = insights;
        this.goals = goals;
    }

    public record Badge(String key, String group, String title, String how, String icon, int points, Instant earnedAt,
                        Integer progress, Integer target) {
    }

    /** @param today recorded something today (else the streak is still alive until midnight) */
    public record Streak(int current, int best, boolean today, List<LocalDate> days) {
    }

    public record Challenge(String key, String title, String how, int progress, int target, LocalDate endsOn, boolean done) {
    }

    /** @param fresh badges earned just now, for the celebration */
    public record Progress(int points, String level, int levelFloor, Integer nextAt, String nextLevel, Streak streak,
                           Challenge challenge, List<Badge> badges, List<String> fresh) {
    }

    @Transactional
    public Progress progress(CurrentUser user) {
        UUID s = user.personalSpaceId();
        ZoneId zone = ZoneId.of(jdbc.sql("select time_zone from users where id = :u").param("u", user.id()).query(String.class).single());
        LocalDate today = LocalDate.now(zone);
        Streak streak = streak(user, zone, today);

        Map<String, int[]> progress = new HashMap<>();
        Set<String> met = new TreeSet<>();
        long entries = count("select count(*) from transactions where space_id = :s and deleted_at is null", s);
        progress.put("first-entry", new int[]{(int) Math.min(entries, 1), 1});
        if (entries > 0) met.add("first-entry");
        if (count("select count(*) from budget_limits where space_id = :s", s) > 0) met.add("budget-set");
        if (count("select count(*) from commitments where space_id = :s", s) > 0) met.add("bills-planned");
        if (count("select count(*) from goals where space_id = :s", s) > 0) met.add("goal-setter");
        progress.put("streak-7", new int[]{Math.min(streak.best(), 7), 7});
        progress.put("streak-30", new int[]{Math.min(streak.best(), 30), 30});
        if (streak.best() >= 7) met.add("streak-7");
        if (streak.best() >= 30) met.add("streak-30");
        if (noSpendDay(s, today)) met.add("no-spend-day");
        if (count("select count(*) from captured_items where user_id = :s and status = 'ADDED'", user.id()) > 0) met.add("auto-pilot");
        if (onBudget(user, s, today)) met.add("on-budget");
        if (saver(s, today)) met.add("saver-20");
        InsightsService.Basis basis = insights.basis(user, s);
        if (basis.cash() != null && basis.spent() > 0) {
            progress.put("cushion-3", new int[]{(int) Math.min(100, Math.round(100.0 * basis.cash() / (3.0 * basis.spent()))), 100});
            if (basis.cash() >= 3 * basis.spent()) met.add("cushion-3");
        }
        List<GoalService.Goal> mine = goals.list(user, s, true);
        mine.stream().filter(g -> g.saved() != null && g.target() > 0)
                .mapToInt(g -> (int) Math.min(100, Math.round(100.0 * g.saved() / g.target()))).max()
                .ifPresent(best -> progress.put("goal-reached", new int[]{best, 100}));
        if (mine.stream().anyMatch(g -> g.saved() != null && g.saved() >= g.target())) met.add("goal-reached");
        if (count("select count(*) from group_expenses where created_by = :s", user.id()) > 0) met.add("splitter");
        if (count("select count(*) from group_settlements where created_by = :s", user.id()) > 0) met.add("settled");
        if (count("""
                        select count(*) from space_members m join spaces sp on sp.id = m.space_id
                        where m.user_id = :s and sp.kind = 'HOUSEHOLD'
                          and (select count(*) from space_members o where o.space_id = sp.id) > 1
                        """, user.id()) > 0) met.add("household");

        // New badges: kept, and told about once.
        List<String> fresh = new ArrayList<>();
        Map<String, Instant> earned = earned(user.id());
        for (String key : met) {
            if (!earned.containsKey(key) && jdbc.sql("insert into achievements (user_id, key) values (:u, :k) on conflict do nothing")
                    .param("u", user.id()).param("k", key).update() > 0) {
                fresh.add(key);
                Def d = def(key);
                inbox.notify(user.id(), Inbox.Kind.ACHIEVEMENT, "Badge earned: " + d.title(), d.how(), "/progress", null, "badge:" + user.id() + ":" + key, null);
            }
        }
        earned = earned(user.id());

        Challenge challenge = challenge(user, s, zone, today);
        if (challenge.done()) {
            inbox.notify(user.id(), Inbox.Kind.ACHIEVEMENT, "Challenge done: " + challenge.title(), "Nicely done. A new one starts on Monday.",
                    "/progress", null, "challenge:" + user.id() + ":" + challenge.key() + ":" + today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), null);
        }

        List<Badge> list = new ArrayList<>();
        int points = Math.min(streak.best(), 60) * 2;
        for (Def d : BADGES) {
            Instant at = earned.get(d.key());
            int[] p = progress.get(d.key());
            list.add(new Badge(d.key(), d.group(), d.title(), d.how(), d.icon(), d.points(), at, p == null ? null : p[0], p == null ? null : p[1]));
            if (at != null) {
                points += d.points();
            }
        }
        if (challenge.done()) {
            points += 25;
        }
        String level = LEVELS.getFirst().getValue();
        int floor = 0;
        Integer nextAt = null;
        String nextLevel = null;
        for (int i = 0; i < LEVELS.size(); i++) {
            if (points >= LEVELS.get(i).getKey()) {
                level = LEVELS.get(i).getValue();
                floor = LEVELS.get(i).getKey();
                nextAt = i + 1 < LEVELS.size() ? LEVELS.get(i + 1).getKey() : null;
                nextLevel = i + 1 < LEVELS.size() ? LEVELS.get(i + 1).getValue() : null;
            }
        }
        return new Progress(points, level, floor, nextAt, nextLevel, streak, challenge, list, fresh);
    }

    // ---------------------------------------------------------------- the pieces

    /** Days the person recorded something (in their time zone): entries they made, and auto-captured ones they added. */
    Streak streak(CurrentUser user, ZoneId zone, LocalDate today) {
        List<LocalDate> days = jdbc.sql("""
                        select distinct (created_at at time zone :tz)::date as day from transactions
                        where created_by = :u and created_at > now() - interval '400 days'
                        order by 1
                        """).param("tz", zone.getId()).param("u", user.id()).query(LocalDate.class).list();
        Set<LocalDate> set = new TreeSet<>(days);
        int best = 0;
        int run = 0;
        LocalDate prev = null;
        for (LocalDate d : set) {
            run = prev != null && prev.plusDays(1).equals(d) ? run + 1 : 1;
            best = Math.max(best, run);
            prev = d;
        }
        boolean logged = set.contains(today);
        // Today not yet logged: yesterday's streak is still alive until midnight.
        LocalDate cursor = logged ? today : today.minusDays(1);
        int current = 0;
        while (set.contains(cursor)) {
            current++;
            cursor = cursor.minusDays(1);
        }
        List<LocalDate> recent = set.stream().filter(d -> !d.isBefore(today.minusWeeks(8))).toList();
        return new Streak(current, best, logged, recent);
    }

    /** A day with no spending, between the first week of use and yesterday. */
    private boolean noSpendDay(UUID s, LocalDate today) {
        LocalDate first = jdbc.sql("select min(occurred_on) from transactions where space_id = :s and deleted_at is null").param("s", s)
                .query(LocalDate.class).optional().orElse(null);
        if (first == null || first.plusDays(7).isAfter(today)) {
            return false;
        }
        long spendDays = jdbc.sql("""
                        select count(distinct occurred_on) from transactions
                        where space_id = :s and deleted_at is null and kind = 'EXPENSE' and occurred_on between :from and :to
                        """).param("s", s).param("from", first.plusDays(7)).param("to", today.minusDays(1)).query(Long.class).single();
        long days = today.minusDays(1).toEpochDay() - first.plusDays(7).toEpochDay() + 1;
        return days > 0 && spendDays < days;
    }

    private boolean onBudget(CurrentUser user, UUID s, LocalDate today) {
        BudgetService.Month last = budgets.month(user, s, YearMonth.from(budgets.now(user).period().start()).minusMonths(1));
        List<BudgetService.Budget> set = last.budgets().stream().filter(b -> b.spent() != null).toList();
        return !set.isEmpty() && set.stream().allMatch(b -> b.spent() <= (b.available() != null ? b.available() : b.limit()));
    }

    private boolean saver(UUID s, LocalDate today) {
        LocalDate thisMonth = today.withDayOfMonth(1);
        return jdbc.sql("""
                        select exists (select 1 from (
                          select sum(amount) filter (where kind = 'INCOME') i, sum(amount) filter (where kind = 'EXPENSE') e
                          from transactions where space_id = :s and deleted_at is null and occurred_on >= :from and occurred_on < :to
                          group by date_trunc('month', occurred_on)) m
                        where m.i > 0 and (m.i - coalesce(m.e, 0)) * 5 >= m.i)
                        """).param("s", s).param("from", thisMonth.minusMonths(3)).param("to", thisMonth).query(Boolean.class).single();
    }

    /** One challenge a week, taking turns: log 5 days, 3 no-spend days, or put something towards a goal. */
    private Challenge challenge(CurrentUser user, UUID s, ZoneId zone, LocalDate today) {
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate sunday = monday.plusDays(6);
        int week = today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        switch (week % 3) {
            case 0 -> {
                int days = jdbc.sql("""
                                select count(distinct (created_at at time zone :tz)::date)::int from transactions
                                where created_by = :u and (created_at at time zone :tz)::date between :from and :to
                                """).param("tz", zone.getId()).param("u", user.id()).param("from", monday).param("to", sunday).query(Integer.class).single();
                return new Challenge("log-5", "Log 5 days this week", "Record your money on 5 different days before Sunday ends.", Math.min(days, 5), 5, sunday, days >= 5);
            }
            case 1 -> {
                long spend = jdbc.sql("""
                                select count(distinct occurred_on) from transactions
                                where space_id = :s and deleted_at is null and kind = 'EXPENSE' and occurred_on between :from and :to
                                """).param("s", s).param("from", monday).param("to", today.minusDays(1)).query(Long.class).single();
                int past = (int) (today.toEpochDay() - monday.toEpochDay());
                int free = (int) Math.max(past - spend, 0);
                return new Challenge("no-spend-3", "Three no-spend days", "Have 3 days this week with no spending at all.", Math.min(free, 3), 3, sunday, free >= 3);
            }
            default -> {
                long added = jdbc.sql("""
                                select (select count(*) from goal_contributions c join goals g on g.id = c.goal_id
                                        where g.space_id = :s and c.occurred_on between :from and :to)
                                     + (select count(*) from transactions where space_id = :s and deleted_at is null and goal_id is not null
                                        and occurred_on between :from and :to)
                                """).param("s", s).param("from", monday).param("to", sunday).query(Long.class).single();
                return new Challenge("save-1", "Pay yourself first", "Put something towards a savings goal this week, however small.",
                        (int) Math.min(added, 1), 1, sunday, added >= 1);
            }
        }
    }

    private Map<String, Instant> earned(UUID user) {
        Map<String, Instant> out = new LinkedHashMap<>();
        jdbc.sql("select key, unlocked_at from achievements where user_id = :u").param("u", user)
                .query((rs, n) -> out.put(rs.getString("key"), rs.getTimestamp("unlocked_at").toInstant())).list();
        return out;
    }

    private long count(String sql, UUID id) {
        return jdbc.sql(sql).param("s", id).query(Long.class).single();
    }

    private static Def def(String key) {
        return BADGES.stream().filter(d -> d.key().equals(key)).findFirst().orElseThrow();
    }
}
