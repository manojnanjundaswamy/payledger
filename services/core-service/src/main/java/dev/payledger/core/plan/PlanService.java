package dev.payledger.core.plan;

import dev.payledger.core.common.ApiException;
import dev.payledger.core.user.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Free and Premium (docs/plan/market-and-pricing.md). Free limits only stop adding: nothing over a limit
 * is hidden or locked, and viewing and exporting are never limited. A space's plan is its owner's.
 */
@Service
public class PlanService {

    static final Duration TRIAL = Duration.ofDays(30);

    public enum Plan { FREE, PREMIUM }

    /** What Free allows; Premium has no limits. */
    public enum Limit {
        ACCOUNTS("open accounts", 5),
        BUDGETS("budgets", 5),
        BILLS("bills and savings on your plan", 5),
        BIG_COSTS("big yearly or one-off costs", 1),
        IMPORTS("imports a month", 3);

        final String what;
        public final int free;

        Limit(String what, int free) {
            this.what = what;
            this.free = free;
        }

        String key() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /**
     * @param source         TRIAL, PAID or COMPLIMENTARY while Premium, else null
     * @param trialAvailable the 30-day trial hasn't been used yet
     * @param limits         Free's limits (null on Premium), and {@code usage} the counts they apply to
     */
    public record Status(Plan plan, String source, Instant premiumUntil, boolean trialAvailable,
                         Map<String, Integer> limits, Map<String, Long> usage) {
    }

    private final JdbcClient jdbc;
    private final Set<String> complimentary;

    public PlanService(JdbcClient jdbc, @Value("${payledger.plans.complimentary:}") String complimentary) {
        this.jdbc = jdbc;
        this.complimentary = Arrays.stream(complimentary.split(","))
                .map(e -> e.strip().toLowerCase(Locale.ROOT)).filter(e -> !e.isEmpty()).collect(Collectors.toSet());
    }

    private record Owner(UUID id, String email, Instant premiumUntil, String premiumSource, Instant trialStartedAt) {
    }

    /** Refuses with 403 {@code plan-limit} when a Free space is already at a limit. Call it before adding. */
    public void check(UUID spaceId, Limit limit) {
        check(spaceId, limit, () -> usage(spaceId, limit));
    }

    /**
     * The same, with the caller's own count (a budget counts the month being set). On Free the space row is
     * locked first, so two adds at once can't both pass the count; Premium skips both.
     */
    public void check(UUID spaceId, Limit limit, LongSupplier used) {
        if (plan(owner(spaceId)) == Plan.PREMIUM) {
            return;
        }
        jdbc.sql("select id from spaces where id = :id for update").param("id", spaceId).query(UUID.class).single();
        if (used.getAsLong() >= limit.free) {
            throw new PlanLimitException(limit);
        }
    }

    @Transactional(readOnly = true)
    public Status status(CurrentUser user) {
        Owner owner = ownerById(user.id());
        Plan plan = plan(owner);
        UUID space = jdbc.sql("select id from spaces where created_by = :id and kind = 'PERSONAL'")
                .param("id", user.id()).query(UUID.class).single();
        Map<String, Integer> limits = null;
        if (plan == Plan.FREE) {
            limits = new LinkedHashMap<>();
            for (Limit l : Limit.values()) {
                limits.put(l.key(), l.free);
            }
        }
        Map<String, Long> usage = new LinkedHashMap<>();
        for (Limit l : Limit.values()) {
            usage.put(l.key(), usage(space, l));
        }
        String source = plan == Plan.FREE ? null : complimentary.contains(owner.email().toLowerCase(Locale.ROOT)) ? "COMPLIMENTARY" : owner.premiumSource();
        return new Status(plan, source, plan == Plan.PREMIUM && "COMPLIMENTARY".equals(source) ? null : owner.premiumUntil(),
                owner.trialStartedAt() == null, limits, usage);
    }

    /** Starts the 30-day trial, once per user; no card needed. */
    @Transactional
    public Status startTrial(CurrentUser user) {
        int started = jdbc.sql("""
                        update users set trial_started_at = now(), premium_source = 'TRIAL',
                            premium_until = greatest(coalesce(premium_until, now()), now()) + cast(:days || ' days' as interval)
                        where id = :id and trial_started_at is null
                        """)
                .param("id", user.id()).param("days", String.valueOf(TRIAL.toDays()))
                .update();
        if (started == 0) {
            throw new TrialUsedException();
        }
        return status(user);
    }

    /** How much of a limit a space uses now. */
    public long usage(UUID spaceId, Limit limit) {
        String sql = switch (limit) {
            case ACCOUNTS -> "select count(*) from money_accounts where space_id = :spaceId and archived_at is null";
            // Budgeted categories in the latest month that has any (a limit set for "from now on").
            case BUDGETS -> """
                    select count(*) from (
                        select distinct on (category_id) category_id, amount from budget_limits
                        where space_id = :spaceId and effective_month <= date_trunc('month', now() + interval '1 month')
                        order by category_id, effective_month desc) latest
                    where amount is not null
                    """;
            case BILLS -> """
                    select count(*) from commitments where space_id = :spaceId and (ends_on is null or ends_on >= current_date)
                      and not (kind = 'BILL' and (every_months >= 6 or coalesce(ends_on = first_due, false)))
                    """;
            case BIG_COSTS -> """
                    select count(*) from commitments where space_id = :spaceId and (ends_on is null or ends_on >= current_date)
                      and kind = 'BILL' and (every_months >= 6 or coalesce(ends_on = first_due, false))
                    """;
            case IMPORTS -> """
                    select count(*) from imports where space_id = :spaceId and undone_at is null
                      and created_at >= date_trunc('month', now())
                    """;
        };
        return jdbc.sql(sql).param("spaceId", spaceId).query(Long.class).single();
    }

    private Plan plan(Owner owner) {
        if (complimentary.contains(owner.email().toLowerCase(Locale.ROOT))) {
            return Plan.PREMIUM;
        }
        return owner.premiumUntil() != null && owner.premiumUntil().isAfter(Instant.now()) ? Plan.PREMIUM : Plan.FREE;
    }

    private Owner owner(UUID spaceId) {
        return jdbc.sql("""
                        select u.id, u.email, u.premium_until, u.premium_source, u.trial_started_at
                        from spaces s join users u on u.id = s.created_by where s.id = :spaceId
                        """)
                .param("spaceId", spaceId).query(Owner.class).single();
    }

    private Owner ownerById(UUID userId) {
        return jdbc.sql("select id, email, premium_until, premium_source, trial_started_at from users where id = :id")
                .param("id", userId).query(Owner.class).single();
    }

    static class PlanLimitException extends ApiException {
        PlanLimitException(Limit limit) {
            super(HttpStatus.FORBIDDEN, "plan-limit",
                    "the Free plan allows " + limit.free + " " + limit.what + "; Premium has no limit");
        }
    }

    static class TrialUsedException extends ApiException {
        TrialUsedException() {
            super(HttpStatus.CONFLICT, "trial-used", "the 30-day trial has already been used");
        }
    }
}
