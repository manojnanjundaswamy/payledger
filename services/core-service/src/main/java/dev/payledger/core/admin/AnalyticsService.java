package dev.payledger.core.admin;

import dev.payledger.core.plan.PlanService;
import dev.payledger.core.user.ActivityTracker;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The admin dashboard's numbers. Aggregates only: counts of people and of things they made, never amounts
 * or descriptions. Days are in the business time zone (Asia/Kolkata by default).
 */
@Service
public class AnalyticsService {

    static final int COHORT_WEEKS = 8;

    private final JdbcClient jdbc;
    private final PlanService plans;
    private final ZoneId zone;

    AnalyticsService(JdbcClient jdbc, PlanService plans, ActivityTracker activity) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.zone = activity.zone();
    }

    ZoneId zone() {
        return zone;
    }

    /** @param newToday, new7Days, new30Days sign-ups (a user is created on their first visit after verifying) */
    public record Users(long total, long newToday, long new7Days, long new30Days) {
    }

    /** Distinct users active today, in the last 7 days and in the last 30 days (today included). */
    public record Active(long daily, long weekly, long monthly) {
    }

    /**
     * Premium by what gave it (GRANTED: given by staff). {@code convertedFromTrial}: paying users who tried Premium first.
     * {@code trialsEndingSoon}: trials ending in the next 7 days.
     */
    public record Plans(long free, long trial, long paid, long granted, long complimentary, long trialsStarted30Days,
                        long trialsEndingSoon, long convertedFromTrial) {
    }

    public record Day(LocalDate day, long signups, long active) {
    }

    /** How many users have used each feature at least once. */
    public record Adoption(long users, long accounts, long transactions, long budgets, long bills, long imports, long groups) {
    }

    /** Things made in the last 30 days (counts). */
    public record Usage(long transactions, long groupExpenses, long imports, long groups) {
    }

    /**
     * A week's sign-ups and the share of them active in each later week ({@code retained[0]} is the sign-up week,
     * so 1.0 or close); weeks not reached yet are left out, and the current week counts so far.
     */
    public record Cohort(LocalDate week, long size, List<Double> retained) {
    }

    /**
     * How the service is doing over the last 24 hours: requests, client errors, server errors, the 95th percentile
     * response time, sign-ins and sign-ups, and emails waiting or given up on.
     */
    public record Platform(long requests24h, long clientErrors24h, long serverErrors24h, long p95Ms, long signIns24h, long signUps24h,
                           long emailsPending, long emailsFailed) {
    }

    public record Overview(LocalDate today, String zone, Users users, Active active, Plans plans, List<Day> days,
                           Adoption adoption, Usage usage, List<Cohort> cohorts, Platform platform) {
    }

    @Transactional(readOnly = true)
    public Overview overview(int days) {
        LocalDate today = LocalDate.now(zone);
        LocalDate from = today.minusDays(days - 1L);
        OffsetDateTime startOfToday = today.atStartOfDay(zone).toOffsetDateTime();
        return new Overview(today, zone.getId(), users(startOfToday), active(today), plans(), days(from, today),
                adoption(), usage(startOfToday.minusDays(29)), cohorts(today), platform());
    }

    private Platform platform() {
        return jdbc.sql("""
                        select (select count(*) from request_log where at > now() - interval '1 day') as requests24h,
                               (select count(*) from request_log where at > now() - interval '1 day' and status between 400 and 499) as client_errors24h,
                               (select count(*) from request_log where at > now() - interval '1 day' and status >= 500) as server_errors24h,
                               (select coalesce(cast(percentile_cont(0.95) within group (order by duration_ms) as bigint), 0)
                                  from request_log where at > now() - interval '1 day') as p95_ms,
                               (select count(*) from user_events where type = 'SIGN_IN' and at > now() - interval '1 day') as sign_ins24h,
                               (select count(*) from user_events where type = 'SIGN_UP' and at > now() - interval '1 day') as sign_ups24h,
                               (select count(*) from email_outbox where sent_at is null and attempts < 6) as emails_pending,
                               (select count(*) from email_outbox where sent_at is null and attempts >= 6) as emails_failed
                        """).query(Platform.class).single();
    }

    private Users users(OffsetDateTime startOfToday) {
        return jdbc.sql("""
                        select count(*) as total,
                               count(*) filter (where created_at >= :today) as new_today,
                               count(*) filter (where created_at >= :d7) as new7_days,
                               count(*) filter (where created_at >= :d30) as new30_days
                        from users
                        """)
                .param("today", startOfToday).param("d7", startOfToday.minusDays(6)).param("d30", startOfToday.minusDays(29))
                .query(Users.class).single();
    }

    private Active active(LocalDate today) {
        return jdbc.sql("""
                        select count(distinct user_id) filter (where day = :today) as daily,
                               count(distinct user_id) filter (where day > :d7) as weekly,
                               count(distinct user_id) as monthly
                        from user_activity_days where day > :d30
                        """)
                .param("today", today).param("d7", today.minusDays(7)).param("d30", today.minusDays(30))
                .query(Active.class).single();
    }

    private Plans plans() {
        // An empty "in ()" is invalid SQL; no email is "" so this matches nobody.
        Set<String> complimentary = plans.complimentaryEmails().isEmpty() ? Set.of("") : plans.complimentaryEmails();
        return jdbc.sql("""
                        with p as (
                            select lower(email) in (:complimentary) as comp, premium_until > now() as premium,
                                   premium_source, premium_until, trial_started_at
                            from users)
                        select count(*) filter (where not comp and not coalesce(premium, false)) as free,
                               count(*) filter (where not comp and premium and premium_source = 'TRIAL') as trial,
                               count(*) filter (where not comp and premium and premium_source = 'PAID') as paid,
                               count(*) filter (where not comp and premium and premium_source = 'GRANTED') as granted,
                               count(*) filter (where comp) as complimentary,
                               count(*) filter (where trial_started_at >= now() - interval '30 days') as trials_started30_days,
                               count(*) filter (where not comp and premium and premium_source = 'TRIAL'
                                                 and premium_until < now() + interval '7 days') as trials_ending_soon,
                               count(*) filter (where premium and premium_source = 'PAID' and trial_started_at is not null) as converted_from_trial
                        from p
                        """)
                .param("complimentary", complimentary)
                .query(Plans.class).single();
    }

    private List<Day> days(LocalDate from, LocalDate today) {
        Map<LocalDate, Long> signups = new HashMap<>();
        jdbc.sql("""
                        select cast(created_at at time zone :zone as date) as day, count(*) as n from users
                        where created_at >= :from group by 1
                        """)
                .param("zone", zone.getId()).param("from", from.atStartOfDay(zone).toOffsetDateTime())
                .query((rs, i) -> signups.put(rs.getObject("day", LocalDate.class), rs.getLong("n"))).list();
        Map<LocalDate, Long> active = new HashMap<>();
        jdbc.sql("select day, count(*) as n from user_activity_days where day >= :from group by day")
                .param("from", from)
                .query((rs, i) -> active.put(rs.getObject("day", LocalDate.class), rs.getLong("n"))).list();
        List<Day> result = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            result.add(new Day(d, signups.getOrDefault(d, 0L), active.getOrDefault(d, 0L)));
        }
        return result;
    }

    private Adoption adoption() {
        return jdbc.sql("""
                        select count(*) as users,
                               count(*) filter (where exists (select 1 from money_accounts a where a.space_id = s.id)) as accounts,
                               count(*) filter (where exists (select 1 from transactions t where t.space_id = s.id)) as transactions,
                               count(*) filter (where exists (select 1 from budget_limits b where b.space_id = s.id)) as budgets,
                               count(*) filter (where exists (select 1 from commitments c where c.space_id = s.id)) as bills,
                               count(*) filter (where exists (select 1 from imports i where i.space_id = s.id)) as imports,
                               count(*) filter (where exists (select 1 from group_members m where m.user_id = u.id)) as groups
                        from users u join spaces s on s.created_by = u.id and s.kind = 'PERSONAL'
                        """)
                .query(Adoption.class).single();
    }

    private Usage usage(OffsetDateTime since) {
        return jdbc.sql("""
                        select (select count(*) from transactions where created_at >= :since) as transactions,
                               (select count(*) from group_expenses where created_at >= :since) as group_expenses,
                               (select count(*) from imports where created_at >= :since) as imports,
                               (select count(*) from groups where created_at >= :since) as groups
                        """)
                .param("since", since).query(Usage.class).single();
    }

    private List<Cohort> cohorts(LocalDate today) {
        LocalDate thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate first = thisWeek.minusWeeks(COHORT_WEEKS - 1L);
        Map<LocalDate, Long> sizes = new HashMap<>();
        jdbc.sql("""
                        select cast(date_trunc('week', created_at at time zone :zone) as date) as week, count(*) as n
                        from users where created_at >= :from group by 1
                        """)
                .param("zone", zone.getId()).param("from", first.atStartOfDay(zone).toOffsetDateTime())
                .query((rs, i) -> sizes.put(rs.getObject("week", LocalDate.class), rs.getLong("n"))).list();
        Map<LocalDate, Map<Integer, Long>> active = new HashMap<>();
        jdbc.sql("""
                        with c as (select id, cast(date_trunc('week', created_at at time zone :zone) as date) as week
                                   from users where created_at >= :from)
                        select c.week, (a.day - c.week) / 7 as k, count(distinct c.id) as n
                        from c join user_activity_days a on a.user_id = c.id and a.day >= c.week
                        group by 1, 2
                        """)
                .param("zone", zone.getId()).param("from", first.atStartOfDay(zone).toOffsetDateTime())
                .query((rs, i) -> active.computeIfAbsent(rs.getObject("week", LocalDate.class), w -> new HashMap<>())
                        .put(rs.getInt("k"), rs.getLong("n"))).list();
        List<Cohort> result = new ArrayList<>();
        for (LocalDate week = first; !week.isAfter(thisWeek); week = week.plusWeeks(1)) {
            long size = sizes.getOrDefault(week, 0L);
            List<Double> retained = new ArrayList<>();
            // Week k is shown once it has started; the current week counts so far.
            long weeksSince = ChronoUnit.WEEKS.between(week, thisWeek);
            for (int k = 0; k <= weeksSince; k++) {
                long n = active.getOrDefault(week, Map.of()).getOrDefault(k, 0L);
                retained.add(size == 0 ? 0.0 : (double) n / size);
            }
            result.add(new Cohort(week, size, retained));
        }
        return result;
    }
}
