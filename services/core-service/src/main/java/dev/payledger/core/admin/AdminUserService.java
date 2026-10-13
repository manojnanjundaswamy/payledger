package dev.payledger.core.admin;

import dev.payledger.core.common.ApiException;
import dev.payledger.core.common.NotFoundException;
import dev.payledger.core.plan.PlanService;
import dev.payledger.core.user.CurrentUser;
import dev.payledger.core.user.UserProvisioning;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Users as staff see them: who they are, their plan and how much they use PayLedger (counts, never money).
 * Every change is audited in the same transaction.
 */
@Service
public class AdminUserService {

    /** FREE, or what gives Premium. */
    public enum PlanKind { FREE, TRIAL, PAID, GRANTED, COMPLIMENTARY }

    public record Row(UUID id, String email, String displayName, Instant createdAt, LocalDate lastActiveOn,
                      PlanKind plan, Instant premiumUntil, boolean disabled, StaffRole staffRole) {
    }

    public record Page(List<Row> users, long total) {
    }

    /** How much they use PayLedger: counts only. */
    public record Usage(long accounts, long transactions, long budgets, long bills, long imports, long groups, long activeDays30) {
    }

    public record Detail(Row user, String subject, String locale, String timeZone, String currency, Instant trialStartedAt,
                         Instant disabledAt, Usage usage, List<AuditLog.Entry> history) {
    }

    private final JdbcClient jdbc;
    private final PlanService plans;
    private final StaffService staff;
    private final IdentityAdmin identity;
    private final UserProvisioning provisioning;
    private final AuditLog audit;
    private final ZoneId zone;

    AdminUserService(JdbcClient jdbc, PlanService plans, StaffService staff, IdentityAdmin identity,
                     UserProvisioning provisioning, AuditLog audit, AnalyticsService analytics) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.staff = staff;
        this.identity = identity;
        this.provisioning = provisioning;
        this.audit = audit;
        this.zone = analytics.zone();
    }

    /**
     * @param q      part of an email or name
     * @param plan   one plan kind, or PREMIUM for any of them but FREE
     * @param status ACTIVE or DISABLED
     */
    @Transactional(readOnly = true)
    public Page list(String q, String plan, String status, int limit, int offset) {
        String where = """
                where (cast(:q as text) is null or u.email ilike :like or u.display_name ilike :like)
                  and (cast(:plan as text) is null or (case when cast(:plan as text) = 'PREMIUM' then p.kind <> 'FREE' else p.kind = :plan end))
                  and (cast(:status as text) is null or (cast(:status as text) = 'DISABLED') = (u.disabled_at is not null))
                """;
        String from = """
                from users u
                cross join lateral (select case when lower(u.email) in (:complimentary) then 'COMPLIMENTARY'
                                                when u.premium_until > now() then u.premium_source else 'FREE' end as kind) p
                left join staff_roles r on r.user_id = u.id
                """;
        String like = q == null || q.isBlank() ? null : "%" + q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        var total = jdbc.sql("select count(*) " + from + where)
                .param("q", like).param("like", like).param("plan", plan).param("status", status).param("complimentary", complimentary())
                .query(Long.class).single();
        var rows = jdbc.sql("""
                        select u.id, u.email, u.display_name, u.created_at, u.last_active_on, p.kind as plan,
                               case when p.kind in ('TRIAL', 'PAID', 'GRANTED') then u.premium_until end as premium_until,
                               u.disabled_at is not null as disabled, r.role as staff_role
                        """ + from + where + """
                        order by u.created_at desc, u.id desc limit :limit offset :offset
                        """)
                .param("q", like).param("like", like).param("plan", plan).param("status", status).param("complimentary", complimentary())
                .param("limit", limit).param("offset", offset)
                .query((rs, i) -> row(rs))
                .list();
        return new Page(rows, total);
    }

    @Transactional(readOnly = true)
    public Detail get(UUID id) {
        Row row = row(id);
        record Extra(String subject, String locale, String timeZone, String currency, Instant trialStartedAt, Instant disabledAt) {
        }
        Extra extra = jdbc.sql("select subject, locale, time_zone, currency, trial_started_at, disabled_at from users where id = :id")
                .param("id", id).query(Extra.class).single();
        Usage usage = jdbc.sql("""
                        select (select count(*) from money_accounts where space_id = s.id) as accounts,
                               (select count(*) from transactions where space_id = s.id and deleted_at is null) as transactions,
                               (select count(distinct category_id) from budget_limits where space_id = s.id) as budgets,
                               (select count(*) from commitments where space_id = s.id) as bills,
                               (select count(*) from imports where space_id = s.id) as imports,
                               (select count(*) from group_members where user_id = :id and left_at is null) as groups,
                               (select count(*) from user_activity_days where user_id = :id and day > :since) as active_days30
                        from spaces s where s.created_by = :id and s.kind = 'PERSONAL'
                        """)
                .param("id", id).param("since", LocalDate.now(zone).minusDays(30))
                .query(Usage.class).optional().orElse(new Usage(0, 0, 0, 0, 0, 0, 0));
        return new Detail(row, extra.subject(), extra.locale(), extra.timeZone(), extra.currency(), extra.trialStartedAt(),
                extra.disabledAt(), usage, audit.list(id, 50, 0));
    }

    /**
     * Creates the sign-in account and the PayLedger user, then emails them a link to verify their address and choose a
     * password. Staff never set or see it. {@code premiumUntil} optionally gives Premium from day one.
     */
    @Transactional
    public Detail create(CurrentUser actor, String email, String displayName, LocalDate premiumUntil) {
        String address = email.strip().toLowerCase(Locale.ROOT);
        Long taken = jdbc.sql("select count(*) from users where email = :email").param("email", address).query(Long.class).single();
        if (taken > 0) {
            throw new dev.payledger.core.user.EmailInUseException();
        }
        if (premiumUntil != null) {
            checkUntil(premiumUntil);
        }
        String subject = identity.create(address, displayName.strip());
        CurrentUser user = provisioning.provision(subject, address, displayName.strip());
        Map<String, Object> details = new HashMap<>(Map.of("email", address));
        if (premiumUntil != null) {
            setPremium(user.id(), premiumUntil);
            details.put("premiumUntil", premiumUntil.toString());
        }
        audit.record(actor.id(), "user.create", user.id(), details);
        identity.sendWelcome(subject);
        return get(user.id());
    }

    /** Blocks the user from signing in and from the API; nothing is deleted. */
    @Transactional
    public Detail setDisabled(CurrentUser actor, UUID id, boolean disabled) {
        Detail target = get(id);
        if (target.user().id().equals(actor.id())) {
            throw new Refused("self", "you can't disable your own account");
        }
        if (staff.isConfiguredOwner(target.user().email())) {
            throw new Refused("protected-user", "this owner is set in ADMIN_EMAILS and can't be disabled here");
        }
        if (target.user().staffRole() != null) {
            staff.require(actor, StaffRole.Permission.STAFF_MANAGE);
        }
        int changed = jdbc.sql("update users set disabled_at = " + (disabled ? "now()" : "null")
                        + ", updated_at = now() where id = :id and (disabled_at is null) = :disabled")
                .param("id", id).param("disabled", disabled).update();
        if (changed > 0) {
            audit.record(actor.id(), disabled ? "user.disable" : "user.enable", id, Map.of());
            // Last, so a failure rolls back the change above and the two never disagree.
            identity.setEnabled(target.subject(), !disabled);
        }
        return get(id);
    }

    public enum Email { PASSWORD_RESET, VERIFY_EMAIL }

    @Transactional
    public void sendEmail(CurrentUser actor, UUID id, Email email) {
        Detail target = get(id);
        audit.record(actor.id(), email == Email.PASSWORD_RESET ? "user.email.password-reset" : "user.email.verify", id, Map.of());
        if (email == Email.PASSWORD_RESET) {
            identity.sendPasswordReset(target.subject());
        } else {
            identity.sendVerification(target.subject());
        }
    }

    @Transactional
    public void signOut(CurrentUser actor, UUID id) {
        Detail target = get(id);
        audit.record(actor.id(), "user.sign-out", id, Map.of());
        identity.signOut(target.subject());
    }

    /** Premium until the end of that day (business time zone), given by staff. Replaces a trial or an earlier grant. */
    @Transactional
    public Detail grantPremium(CurrentUser actor, UUID id, LocalDate until) {
        Detail target = get(id);
        checkUntil(until);
        if (target.user().plan() == PlanKind.PAID) {
            throw new Refused("paid-subscription", "this user pays for Premium; change it in billing");
        }
        setPremium(id, until);
        audit.record(actor.id(), "plan.grant", id, Map.of("until", until.toString(), "before", target.user().plan().name()));
        return get(id);
    }

    /** Ends a trial or a grant now. Nothing is lost: Free's limits only stop adding. */
    @Transactional
    public Detail endPremium(CurrentUser actor, UUID id) {
        Detail target = get(id);
        if (target.user().plan() != PlanKind.TRIAL && target.user().plan() != PlanKind.GRANTED) {
            throw new Refused("not-ending", target.user().plan() == PlanKind.FREE ? "this user is on Free"
                    : "only a trial or Premium given by staff can be ended here");
        }
        jdbc.sql("update users set premium_until = now(), updated_at = now() where id = :id").param("id", id).update();
        audit.record(actor.id(), "plan.end", id, Map.of("was", target.user().plan().name()));
        return get(id);
    }

    private void setPremium(UUID id, LocalDate until) {
        jdbc.sql("update users set premium_source = 'GRANTED', premium_until = :until, updated_at = now() where id = :id")
                // The last second of that day, so it shows as that date (not the next) wherever it is displayed.
                .param("id", id).param("until", until.plusDays(1).atStartOfDay(zone).toOffsetDateTime().minusSeconds(1)).update();
    }

    private void checkUntil(LocalDate until) {
        LocalDate today = LocalDate.now(zone);
        if (until.isBefore(today) || until.isAfter(today.plusYears(5))) {
            throw new Refused("bad-date", "choose a date from today to five years ahead");
        }
    }

    private Row row(UUID id) {
        return jdbc.sql("""
                        select u.id, u.email, u.display_name, u.created_at, u.last_active_on, p.kind as plan,
                               case when p.kind in ('TRIAL', 'PAID', 'GRANTED') then u.premium_until end as premium_until,
                               u.disabled_at is not null as disabled, r.role as staff_role
                        from users u
                        cross join lateral (select case when lower(u.email) in (:complimentary) then 'COMPLIMENTARY'
                                                        when u.premium_until > now() then u.premium_source else 'FREE' end as kind) p
                        left join staff_roles r on r.user_id = u.id
                        where u.id = :id
                        """)
                .param("id", id).param("complimentary", complimentary())
                .query((rs, i) -> row(rs)).optional()
                .map(r -> r.staffRole() == null && staff.isConfiguredOwner(r.email()) ? withRole(r, StaffRole.OWNER) : r)
                .orElseThrow(() -> new NotFoundException("user"));
    }

    private Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        String role = rs.getString("staff_role");
        Row r = new Row(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"),
                rs.getTimestamp("created_at").toInstant(), rs.getObject("last_active_on", LocalDate.class),
                PlanKind.valueOf(rs.getString("plan")),
                rs.getTimestamp("premium_until") == null ? null : rs.getTimestamp("premium_until").toInstant(),
                rs.getBoolean("disabled"), role == null ? null : StaffRole.valueOf(role));
        return r.staffRole() == null && staff.isConfiguredOwner(r.email()) ? withRole(r, StaffRole.OWNER) : r;
    }

    private static Row withRole(Row r, StaffRole role) {
        return new Row(r.id(), r.email(), r.displayName(), r.createdAt(), r.lastActiveOn(), r.plan(), r.premiumUntil(), r.disabled(), role);
    }

    private Set<String> complimentary() {
        // An empty "in ()" is invalid SQL; no email is "" so this matches nobody.
        return plans.complimentaryEmails().isEmpty() ? Set.of("") : plans.complimentaryEmails();
    }

    static class Refused extends ApiException {
        Refused(String code, String message) {
            super(HttpStatus.CONFLICT, code, message);
        }
    }
}
