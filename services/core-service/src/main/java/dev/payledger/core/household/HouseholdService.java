package dev.payledger.core.household;

import dev.payledger.core.common.ApiException;
import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.common.NotFoundException;
import dev.payledger.core.common.Uuid7;
import dev.payledger.core.money.CategoryService;
import dev.payledger.core.notify.Email;
import dev.payledger.core.notify.Inbox;
import dev.payledger.core.notify.Notifications;
import dev.payledger.core.plan.PlanService;
import dev.payledger.core.user.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Households (product plan 2.4): a space a family shares. Every money endpoint already works on any space you're a
 * member of ({@code SpaceAccess}), so a household gets accounts, budgets, the plan, goals and reports for free; this
 * adds what's particular to sharing: members and roles, invites, and who paid what. Personal spaces are never shared.
 *
 * <p>Roles: OWNER (one; manages everything), PARTNER (manages members too), MEMBER (adds and edits money), VIEWER
 * (looks only: SpaceAccess refuses their writes).
 */
@Service
public class HouseholdService {

    static final int MAX_MEMBERS = 12;
    static final Set<String> INVITABLE = Set.of("PARTNER", "MEMBER", "VIEWER");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final PlanService plans;
    private final CategoryService categories;
    private final Inbox inbox;
    private final Notifications notifications;

    HouseholdService(JdbcClient jdbc, PlanService plans, CategoryService categories, Inbox inbox, Notifications notifications) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.categories = categories;
        this.inbox = inbox;
        this.notifications = notifications;
    }

    public record Summary(UUID id, String name, String currency, String role, int members) {
    }

    public record Member(UUID userId, String name, String email, String role, Instant joinedAt, boolean you) {
    }

    public record Invite(UUID id, String email, String role, Instant createdAt, Instant expiresAt) {
    }

    /** @param share of the month's household spending (0–1) */
    public record Contribution(UUID userId, String name, long spent, int entries, double share) {
    }

    public record Household(UUID id, String name, String currency, String role, List<Member> members, List<Invite> invites,
                            String month, long spent, List<Contribution> contributions) {
    }

    public record InviteInfo(String household, String invitedBy, String email, String role, boolean forYou, boolean alreadyMember, boolean expired) {
    }

    @Transactional(readOnly = true)
    public List<Summary> list(CurrentUser user) {
        return jdbc.sql("""
                        select s.id, s.name, s.currency, m.role, (select count(*) from space_members x where x.space_id = s.id)::int as members
                        from space_members m join spaces s on s.id = m.space_id
                        where m.user_id = :u and s.kind = 'HOUSEHOLD' order by s.name
                        """).param("u", user.id()).query(Summary.class).list();
    }

    @Transactional
    public Household create(CurrentUser user, String name, String currency) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 60) {
            throw new InvalidRequestException("invalid-name", "give the household a name, up to 60 characters");
        }
        if (plans.status(user).plan() != PlanService.Plan.PREMIUM) {
            throw new PremiumOnly();
        }
        if (list(user).stream().filter(h -> h.role().equals("OWNER")).count() >= 3) {
            throw new InvalidRequestException("too-many-households", "you can run up to three households");
        }
        UUID id = Uuid7.next();
        String cur = currency != null && currency.matches("[A-Z]{3}") ? currency
                : jdbc.sql("select currency from spaces where id = :s").param("s", user.personalSpaceId()).query(String.class).single();
        jdbc.sql("insert into spaces (id, kind, name, currency, created_by) values (:id, 'HOUSEHOLD', :name, :cur, :u)")
                .param("id", id).param("name", n).param("cur", cur).param("u", user.id()).update();
        jdbc.sql("insert into space_members (space_id, user_id, role) values (:id, :u, 'OWNER')").param("id", id).param("u", user.id()).update();
        categories.seedDefaults(id);
        return get(user, id, null);
    }

    @Transactional(readOnly = true)
    public Household get(CurrentUser user, UUID id, String month) {
        String role = role(user, id);
        record Row(String name, String currency) {
        }
        Row s = jdbc.sql("select name, currency from spaces where id = :id").param("id", id).query(Row.class).single();
        List<Member> members = jdbc.sql("""
                        select u.id as user_id, u.display_name as name, u.email, m.role, m.joined_at, u.id = :me as you
                        from space_members m join users u on u.id = m.user_id where m.space_id = :id
                        order by m.role = 'OWNER' desc, m.joined_at
                        """).param("id", id).param("me", user.id()).query(Member.class).list();
        List<Invite> invites = manages(role) ? jdbc.sql("""
                        select id, email, role, created_at, expires_at from space_invites
                        where space_id = :id and accepted_at is null and revoked_at is null and expires_at > now() order by created_at
                        """).param("id", id).query(Invite.class).list() : List.of();
        YearMonth ym = month != null && month.matches("\\d{4}-\\d{2}") ? YearMonth.parse(month) : YearMonth.now(zone(user));
        record Spent(UUID userId, String name, long spent, int entries) {
        }
        // Who recorded the household's spending: the closest thing to "who paid" without asking every time.
        List<Spent> rows = jdbc.sql("""
                        select t.created_by as user_id, u.display_name as name, sum(t.amount) as spent, count(*)::int as entries
                        from transactions t join users u on u.id = t.created_by
                        where t.space_id = :id and t.kind = 'EXPENSE' and t.deleted_at is null and t.occurred_on between :from and :to
                        group by t.created_by, u.display_name order by spent desc
                        """).param("id", id).param("from", ym.atDay(1)).param("to", ym.atEndOfMonth()).query(Spent.class).list();
        long total = rows.stream().mapToLong(Spent::spent).sum();
        List<Contribution> contributions = members.stream().map(m -> {
            Spent r = rows.stream().filter(x -> x.userId().equals(m.userId())).findFirst().orElse(null);
            long spent = r == null ? 0 : r.spent();
            return new Contribution(m.userId(), m.name(), spent, r == null ? 0 : r.entries(), total == 0 ? 0 : (double) spent / total);
        }).toList();
        return new Household(id, s.name(), s.currency(), role, members, invites, ym.toString(), total, contributions);
    }

    @Transactional
    public Household rename(CurrentUser user, UUID id, String name) {
        if (!role(user, id).equals("OWNER")) {
            throw new NotAllowed("only the owner can rename the household");
        }
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 60) {
            throw new InvalidRequestException("invalid-name", "give the household a name, up to 60 characters");
        }
        jdbc.sql("update spaces set name = :n where id = :id").param("n", n).param("id", id).update();
        return get(user, id, null);
    }

    /** Someone with an account joins at once (and is told); anyone else gets an email with a link, valid 14 days. */
    @Transactional
    public Household invite(CurrentUser user, UUID id, String email, String role) {
        if (!manages(role(user, id))) {
            throw new NotAllowed("only the owner or a partner can invite people");
        }
        String r = role == null ? "MEMBER" : role.toUpperCase(Locale.ROOT);
        if (!INVITABLE.contains(r)) {
            throw new InvalidRequestException("invalid-role", "a member can be a partner, a member or a viewer");
        }
        String e = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (!e.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") || e.length() > 254) {
            throw new InvalidRequestException("invalid-email", "enter an email address");
        }
        long members = jdbc.sql("select count(*) from space_members where space_id = :id").param("id", id).query(Long.class).single();
        if (members >= MAX_MEMBERS) {
            throw new InvalidRequestException("household-full", "a household has at most " + MAX_MEMBERS + " members");
        }
        String household = name(id);
        UUID existing = jdbc.sql("select id from users where lower(email) = :e").param("e", e).query(UUID.class).optional().orElse(null);
        if (existing != null) {
            int added = jdbc.sql("insert into space_members (space_id, user_id, role) values (:id, :u, :r) on conflict do nothing")
                    .param("id", id).param("u", existing).param("r", r).update();
            if (added == 0) {
                throw new InvalidRequestException("already-member", "they're already in this household");
            }
            inbox.notify(existing, Inbox.Kind.HOUSEHOLD, user.displayName() + " added you to " + household,
                    "Switch to it from the top bar to see its money.", "/household/" + id, user.id(), "household-added:" + id + ":" + existing,
                    Email.titled(user.displayName() + " added you to " + household)
                            .heading("You're in " + household)
                            .para(user.displayName() + " added you to the household " + household + " on PayLedger, to plan and track your money together. Your own accounts stay private.")
                            .button("Open " + household, notifications.url("/household/" + id))
                            .footer("You got this because someone added you to a household.", notifications.settingsUrl()));
            return get(user, id, null);
        }
        String token = token();
        jdbc.sql("update space_invites set revoked_at = now() where space_id = :id and lower(email) = :e and accepted_at is null and revoked_at is null")
                .param("id", id).param("e", e).update();
        jdbc.sql("""
                        insert into space_invites (id, space_id, email, role, token_hash, invited_by, expires_at)
                        values (:i, :id, :e, :r, :h, :u, now() + interval '14 days')
                        """).param("i", Uuid7.next()).param("id", id).param("e", e).param("r", r).param("h", hash(token)).param("u", user.id()).update();
        notifications.toAddress(e, "HOUSEHOLD_INVITE", "household-invite:" + id + ":" + e + ":" + Instant.now().getEpochSecond() / 60,
                Email.titled(user.displayName() + " invited you to " + household + " on PayLedger")
                        .heading(user.displayName() + " invited you to " + household)
                        .para("Plan and track the family's money together: shared accounts, budgets and bills, with your own money kept private. Signing up is free.")
                        .button("Join " + household, notifications.url("/household/join?token=" + token))
                        .footer("The link works for 14 days. You got this because " + user.displayName() + " entered your email. If you don't know them, ignore it.", null));
        return get(user, id, null);
    }

    @Transactional
    public Household revokeInvite(CurrentUser user, UUID id, UUID inviteId) {
        if (!manages(role(user, id))) {
            throw new NotAllowed("only the owner or a partner can cancel invites");
        }
        jdbc.sql("update space_invites set revoked_at = now() where id = :i and space_id = :id and accepted_at is null")
                .param("i", inviteId).param("id", id).update();
        return get(user, id, null);
    }

    /** What an invite link is for, before accepting it. */
    @Transactional(readOnly = true)
    public InviteInfo inviteInfo(CurrentUser user, String token) {
        record Row(UUID spaceId, String household, String invitedBy, String email, String role, boolean expired, boolean used) {
        }
        Row r = jdbc.sql("""
                        select i.space_id, s.name as household, u.display_name as invited_by, i.email, i.role,
                               i.expires_at < now() as expired, (i.accepted_at is not null or i.revoked_at is not null) as used
                        from space_invites i join spaces s on s.id = i.space_id join users u on u.id = i.invited_by where i.token_hash = :h
                        """).param("h", hash(token)).query(Row.class).optional().orElseThrow(() -> new NotFoundException("invite"));
        boolean member = jdbc.sql("select exists (select 1 from space_members where space_id = :s and user_id = :u)")
                .param("s", r.spaceId()).param("u", user.id()).query(Boolean.class).single();
        return new InviteInfo(r.household(), r.invitedBy(), mask(r.email()), r.role(), r.email().equalsIgnoreCase(user.email()), member,
                r.expired() || r.used());
    }

    /** Joins with an invite link: it must be for your own email address, unused and not expired. */
    @Transactional
    public Household join(CurrentUser user, String token) {
        record Row(UUID id, UUID spaceId, String email, String role) {
        }
        Row r = jdbc.sql("""
                        select id, space_id, email, role from space_invites
                        where token_hash = :h and accepted_at is null and revoked_at is null and expires_at > now() for update
                        """).param("h", hash(token)).query(Row.class).optional()
                .orElseThrow(() -> new InvalidRequestException("invite-invalid", "this invite has expired or was already used; ask for a new one"));
        if (!r.email().equalsIgnoreCase(user.email())) {
            throw new InvalidRequestException("invite-other-email", "this invite is for another email address; sign in with that one");
        }
        jdbc.sql("insert into space_members (space_id, user_id, role) values (:s, :u, :r) on conflict do nothing")
                .param("s", r.spaceId()).param("u", user.id()).param("r", r.role()).update();
        jdbc.sql("update space_invites set accepted_at = now() where id = :i").param("i", r.id()).update();
        UUID owner = jdbc.sql("select created_by from spaces where id = :s").param("s", r.spaceId()).query(UUID.class).single();
        inbox.notify(owner, Inbox.Kind.HOUSEHOLD, user.displayName() + " joined " + name(r.spaceId()), null, "/household/" + r.spaceId(), user.id(),
                "household-joined:" + r.spaceId() + ":" + user.id(), null);
        return get(user, r.spaceId(), null);
    }

    @Transactional
    public Household setRole(CurrentUser user, UUID id, UUID memberId, String role) {
        if (!role(user, id).equals("OWNER")) {
            throw new NotAllowed("only the owner can change roles");
        }
        if (memberId.equals(user.id())) {
            throw new InvalidRequestException("own-role", "hand over ownership by making someone else the owner");
        }
        String r = role == null ? "" : role.toUpperCase(Locale.ROOT);
        if (r.equals("OWNER")) {
            // Hand over: the new owner owns the space (its plan counts), the old one becomes a partner.
            jdbc.sql("update space_members set role = 'PARTNER' where space_id = :id and user_id = :me").param("id", id).param("me", user.id()).update();
            jdbc.sql("update spaces set created_by = :u where id = :id").param("u", memberId).param("id", id).update();
        } else if (!INVITABLE.contains(r)) {
            throw new InvalidRequestException("invalid-role", "a member can be a partner, a member or a viewer");
        }
        int n = jdbc.sql("update space_members set role = :r where space_id = :id and user_id = :u").param("r", r).param("id", id).param("u", memberId).update();
        if (n == 0) {
            throw new NotFoundException("member");
        }
        return get(user, id, null);
    }

    /** The owner or a partner removes someone; anyone can leave. What they recorded stays in the household. */
    @Transactional
    public void remove(CurrentUser user, UUID id, UUID memberId) {
        String mine = role(user, id);
        boolean self = memberId.equals(user.id());
        if (!self && !manages(mine)) {
            throw new NotAllowed("only the owner or a partner can remove people");
        }
        String theirs = jdbc.sql("select role from space_members where space_id = :id and user_id = :u").param("id", id).param("u", memberId)
                .query(String.class).optional().orElseThrow(() -> new NotFoundException("member"));
        if (theirs.equals("OWNER")) {
            throw new InvalidRequestException("owner-stays", self ? "make someone else the owner before you leave"
                    : "the owner can't be removed");
        }
        jdbc.sql("delete from space_members where space_id = :id and user_id = :u").param("id", id).param("u", memberId).update();
    }

    // ---------------------------------------------------------------- internals

    /** Your role here; a household you're not in is "not found", like any space. */
    private String role(CurrentUser user, UUID id) {
        return jdbc.sql("""
                        select m.role from space_members m join spaces s on s.id = m.space_id
                        where s.id = :id and s.kind = 'HOUSEHOLD' and m.user_id = :u
                        """).param("id", id).param("u", user.id()).query(String.class).optional()
                .orElseThrow(() -> new NotFoundException("household"));
    }

    private static boolean manages(String role) {
        return role.equals("OWNER") || role.equals("PARTNER");
    }

    private String name(UUID id) {
        return jdbc.sql("select name from spaces where id = :id").param("id", id).query(String.class).single();
    }

    private ZoneId zone(CurrentUser user) {
        return ZoneId.of(jdbc.sql("select time_zone from users where id = :u").param("u", user.id()).query(String.class).single());
    }

    private static String token() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((token == null ? "" : token).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** "ri***@gmail.com": enough to recognise, not enough to harvest. */
    static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 2 ? "***" + email.substring(Math.max(at, 0)) : email.substring(0, 2) + "***" + email.substring(at);
    }

    static class NotAllowed extends ApiException {
        NotAllowed(String message) {
            super(HttpStatus.FORBIDDEN, "not-allowed", message);
        }
    }

    static class PremiumOnly extends ApiException {
        PremiumOnly() {
            super(HttpStatus.FORBIDDEN, "plan-limit", "households are part of Premium; start the free trial to try one");
        }
    }
}
