package dev.payledger.core.group;

import dev.payledger.core.common.ApiException;
import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.common.NotFoundException;
import dev.payledger.core.common.Uuid7;
import dev.payledger.core.user.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Groups and their members (docs/adr/0007-groups-and-splitting.md). Only members see a group; anyone else
 * gets 404. A member is a registered user or a placeholder (a name, maybe an email) until they join.
 */
@Service
public class GroupService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private GroupHistory history;

    @org.springframework.beans.factory.annotation.Autowired
    void setHistory(GroupHistory history) {
        this.history = history;
    }

    private final JdbcClient jdbc;

    private final dev.payledger.core.notify.Notifications notifications;
    private final dev.payledger.core.notify.Inbox inbox;

    public GroupService(JdbcClient jdbc, dev.payledger.core.notify.Notifications notifications, dev.payledger.core.notify.Inbox inbox) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.inbox = inbox;
    }

    /**
     * @param you          the signed-in user's member id in this group (their balance is under it)
     * @param spent        all live expenses, paise
     * @param net          what you owe the group (positive) or are owed (negative), from the expenses and settlements
     *                     as entered; the ledger's balance is the same once it has caught up
     * @param lastActivity the latest expense or settlement date; null when nothing was added yet
     */
    public record Summary(UUID id, String name, String emoji, String currency, String role, int members, boolean archived, UUID you,
                          long spent, int expenses, long net, java.time.LocalDate lastActivity) {
    }

    /**
     * @param email shown for placeholders invited by email (the inviter typed it); never for people who joined
     * @param you   this member is the signed-in user
     */
    /** @param upiId where this member can be paid (their own setting; only joined members have one) */
    public record Member(UUID id, String name, String email, String role, boolean joined, boolean you, String upiId) {
    }

    public record Group(UUID id, String name, String emoji, String currency, boolean archived, UUID you, List<Member> members) {
    }

    public record Invite(String groupName, String emoji, int members, boolean alreadyMember) {
    }

    @Transactional(readOnly = true)
    public List<Summary> list(CurrentUser user) {
        return jdbc.sql("""
                        select g.id, g.name, g.emoji, g.currency, m.role,
                               (select count(*) from group_members x where x.group_id = g.id and x.left_at is null)::int as members,
                               g.archived_at is not null as archived, m.id as you,
                               coalesce(e.spent, 0) as spent, coalesce(e.count, 0)::int as expenses,
                               coalesce((select sum(p.share - p.paid) from group_expense_parts p
                                         join group_expenses x on x.id = p.expense_id and x.deleted_at is null
                                         where p.member_id = m.id), 0)
                                 - coalesce((select sum(s.amount) from group_settlements s where s.from_member = m.id and s.deleted_at is null), 0)
                                 + coalesce((select sum(s.amount) from group_settlements s where s.to_member = m.id and s.deleted_at is null), 0) as net,
                               greatest(e.last, (select max(s.occurred_on) from group_settlements s where s.group_id = g.id and s.deleted_at is null)) as last_activity
                        from group_members m join groups g on g.id = m.group_id
                        left join lateral (select sum(amount) as spent, count(*) as count, max(occurred_on) as last
                                           from group_expenses where group_id = g.id and deleted_at is null) e on true
                        where m.user_id = :userId and m.left_at is null and not g.direct
                        order by g.archived_at is not null, greatest(e.last, g.created_at::date) desc, g.created_at desc
                        """)
                .param("userId", user.id())
                .query(Summary.class)
                .list();
    }

    @Transactional
    public Group create(CurrentUser user, GroupRequests.Create request) {
        UUID id = Uuid7.next();
        jdbc.sql("insert into groups (id, name, emoji, currency, created_by) values (:id, :name, :emoji, :currency, :userId)")
                .param("id", id).param("name", request.name().strip()).param("emoji", blankToNull(request.emoji()))
                .param("currency", request.currency() != null ? request.currency() : "INR").param("userId", user.id())
                .update();
        jdbc.sql("""
                        insert into group_members (id, group_id, user_id, display_name, role, added_by, joined_at)
                        values (:id, :groupId, :userId, :name, 'OWNER', :userId, now())
                        """)
                .param("id", Uuid7.next()).param("groupId", id).param("userId", user.id()).param("name", truncate(user.displayName()))
                .update();
        history.record(id, user, GroupHistory.Action.GROUP_CREATED, GroupHistory.details("name", request.name().strip(), "emoji", blankToNull(request.emoji())));
        return get(user, id);
    }

    @Transactional(readOnly = true)
    public Group get(CurrentUser user, UUID groupId) {
        Me me = member(user, groupId);
        record Row(UUID id, String name, String emoji, String currency, boolean archived) {
        }
        Row g = jdbc.sql("select id, name, emoji, currency, archived_at is not null as archived from groups where id = :id")
                .param("id", groupId).query(Row.class).single();
        List<Member> members = jdbc.sql("""
                        select m.id, m.display_name as name, case when m.user_id is null then m.email end as email, m.role,
                               m.user_id is not null as joined, coalesce(m.user_id = :userId, false) as you, u.upi_id
                        from group_members m left join users u on u.id = m.user_id
                        where m.group_id = :groupId and m.left_at is null
                        order by m.role = 'OWNER' desc, m.created_at
                        """)
                .param("groupId", groupId).param("userId", user.id())
                .query(Member.class).list();
        return new Group(g.id(), g.name(), g.emoji(), g.currency(), g.archived(), me.id(), members);
    }

    @Transactional
    public Group update(CurrentUser user, UUID groupId, GroupRequests.Update request) {
        Me me = member(user, groupId);
        if (request.archived() != null && !me.role().equals("OWNER")) {
            throw new InvalidRequestException("owner-only", "only the group's owner can archive it");
        }
        record Was(String name, String emoji, boolean archived) {
        }
        Was was = jdbc.sql("select name, emoji, archived_at is not null as archived from groups where id = :id").param("id", groupId).query(Was.class).single();
        jdbc.sql("""
                        update groups set name = coalesce(:name, name), emoji = coalesce(:emoji, emoji),
                            archived_at = case when cast(:archived as boolean) is null then archived_at
                                               when cast(:archived as boolean) then coalesce(archived_at, now()) else null end
                        where id = :id
                        """)
                .param("id", groupId).param("name", request.name() == null ? null : request.name().strip())
                .param("emoji", request.emoji()).param("archived", request.archived())
                .update();
        java.util.List<java.util.Map<String, Object>> changes = new java.util.ArrayList<>();
        if (request.name() != null && !request.name().strip().equals(was.name())) {
            changes.add(GroupHistory.details("field", "name", "before", was.name(), "after", request.name().strip()));
        }
        if (request.emoji() != null && !request.emoji().equals(was.emoji())) {
            changes.add(GroupHistory.details("field", "emoji", "before", was.emoji(), "after", request.emoji()));
        }
        if (request.archived() != null && request.archived() != was.archived()) {
            changes.add(GroupHistory.details("field", "archived", "before", was.archived(), "after", request.archived()));
        }
        if (!changes.isEmpty()) {
            history.record(groupId, user, GroupHistory.Action.GROUP_UPDATED, GroupHistory.details("changes", changes));
        }
        return get(user, groupId);
    }

    /**
     * Adds a person by name, with an email if known. An email that already has an account adds that person
     * at once; otherwise the member is a placeholder until someone signs up with it or joins by the link.
     */
    @Transactional
    public Group addMember(CurrentUser user, UUID groupId, GroupRequests.AddMember request) {
        member(user, groupId);
        lockGroup(groupId);
        String email = request.email() == null || request.email().isBlank() ? null : request.email().strip().toLowerCase(Locale.ROOT);
        UUID existingUser = null;
        if (email != null) {
            boolean taken = jdbc.sql("""
                            select exists (select 1 from group_members m left join users u on u.id = m.user_id
                                           where m.group_id = :groupId and m.left_at is null and (m.email = :email or u.email = :email))
                            """)
                    .param("groupId", groupId).param("email", email).query(Boolean.class).single();
            if (taken) {
                throw new MemberExistsException();
            }
            existingUser = jdbc.sql("select id from users where email = :email").param("email", email).query(UUID.class).optional().orElse(null);
        }
        UUID memberId = Uuid7.next();
        jdbc.sql("""
                        insert into group_members (id, group_id, user_id, display_name, email, role, added_by, joined_at)
                        values (:id, :groupId, :userId, :name, :email, 'MEMBER', :addedBy, case when cast(:userId as uuid) is null then null else now() end)
                        """)
                .param("id", memberId).param("groupId", groupId).param("userId", existingUser)
                .param("name", request.name().strip()).param("email", email).param("addedBy", user.id())
                .update();
        if (email != null) {
            invite(user, groupId, memberId, email, existingUser);
        }
        history.record(groupId, user, GroupHistory.Action.MEMBER_ADDED, GroupHistory.details(
                "member", request.name().strip(), "email", email, "joined", existingUser != null));
        return get(user, groupId);
    }

    /**
     * Tells the person: someone with an account that they were added (they can turn that off), someone without one
     * how to join (the group's link, which also signs them up).
     */
    private void invite(CurrentUser user, UUID groupId, UUID memberId, String email, UUID existingUser) {
        String group = jdbc.sql("select name from groups where id = :id").param("id", groupId).query(String.class).single();
        String inviter = user.displayName();
        if (existingUser != null) {
            inbox.notify(existingUser, dev.payledger.core.notify.Inbox.Kind.GROUP_MEMBER, inviter + " added you to " + group,
                    "Split expenses and settle up together.", "/groups/" + groupId, user.id(), "group-added:" + memberId,
                    dev.payledger.core.notify.Email.titled(inviter + " added you to " + group)
                            .heading("You're in " + group)
                            .para(inviter + " added you to the group " + group + " on PayLedger, to split expenses and settle up.")
                            .button("Open " + group, notifications.url("/groups/" + groupId))
                            .footer("You got this because someone added you to a group.", notifications.settingsUrl()));
            return;
        }
        String token = jdbc.sql("select token from group_links where group_id = :groupId and revoked_at is null")
                .param("groupId", groupId).query(String.class).optional()
                .orElseGet(() -> newLink(user, groupId));
        notifications.toAddress(email, "GROUP_INVITE", "group-invite:" + memberId,
                dev.payledger.core.notify.Email.titled(inviter + " invited you to split expenses in " + group)
                        .heading(inviter + " invited you to " + group)
                        .para("Track who paid what and settle up in the fewest payments. Join with this link; signing up takes a minute and is free.")
                        .button("Join " + group, notifications.url("/join/" + token))
                        .footer("You got this because " + inviter + " added your email to a group on PayLedger. If you don't know them, ignore it.", null));
    }

    /** Owners can remove anyone but themselves; anyone can leave. The member's history stays. */
    @Transactional
    public void removeMember(CurrentUser user, UUID groupId, UUID memberId) {
        Me me = member(user, groupId);
        record Target(UUID id, String role) {
        }
        Target target = jdbc.sql("select id, role from group_members where id = :id and group_id = :groupId and left_at is null")
                .param("id", memberId).param("groupId", groupId).query(Target.class).optional()
                .orElseThrow(() -> new NotFoundException("member"));
        if (target.role().equals("OWNER")) {
            throw new InvalidRequestException("owner-cannot-leave", "the group's owner can't leave it; archive the group instead");
        }
        if (!me.role().equals("OWNER") && !me.id().equals(memberId)) {
            throw new InvalidRequestException("owner-only", "only the group's owner can remove someone else");
        }
        if (owes(memberId) != 0) {
            throw new MemberHasBalanceException();
        }
        jdbc.sql("update group_members set left_at = now() where id = :id").param("id", memberId).update();
        String name = jdbc.sql("select display_name from group_members where id = :id").param("id", memberId).query(String.class).single();
        history.record(groupId, user, me.id().equals(memberId) ? GroupHistory.Action.MEMBER_LEFT : GroupHistory.Action.MEMBER_REMOVED,
                GroupHistory.details("member", name));
    }

    /**
     * What a member owes (positive) or is owed, from what was entered: exact at once (the ledger's balance
     * follows a moment later), so nobody leaves a group with money still owed either way.
     */
    long owes(UUID memberId) {
        return jdbc.sql("""
                        select coalesce((select sum(p.share - p.paid) from group_expense_parts p
                                         join group_expenses e on e.id = p.expense_id
                                         where p.member_id = :id and e.deleted_at is null), 0)
                             - coalesce((select sum(amount) from group_settlements where from_member = :id and deleted_at is null), 0)
                             + coalesce((select sum(amount) from group_settlements where to_member = :id and deleted_at is null), 0)
                        """)
                .param("id", memberId).query(Long.class).single();
    }

    /** The group's join link token; made on first ask. */
    @Transactional
    public String link(CurrentUser user, UUID groupId) {
        member(user, groupId);
        lockGroup(groupId);
        return jdbc.sql("select token from group_links where group_id = :groupId and revoked_at is null")
                .param("groupId", groupId).query(String.class).optional()
                .orElseGet(() -> newLink(user, groupId));
    }

    /** A new link; the old one stops working. */
    @Transactional
    public String resetLink(CurrentUser user, UUID groupId) {
        member(user, groupId);
        lockGroup(groupId);
        jdbc.sql("update group_links set revoked_at = now() where group_id = :groupId and revoked_at is null")
                .param("groupId", groupId).update();
        history.record(groupId, user, GroupHistory.Action.LINK_RESET, GroupHistory.details());
        return newLink(user, groupId);
    }

    @Transactional(readOnly = true)
    public Invite invite(CurrentUser user, String token) {
        UUID groupId = groupOf(token);
        record Row(String name, String emoji, int members) {
        }
        Row g = jdbc.sql("""
                        select name, emoji, (select count(*) from group_members m where m.group_id = g.id and m.left_at is null)::int as members
                        from groups g where id = :id
                        """)
                .param("id", groupId).query(Row.class).single();
        return new Invite(g.name(), g.emoji(), g.members(), isMember(user, groupId));
    }

    /**
     * Joins by link. A placeholder invited with this user's email becomes them; otherwise they are added by
     * their name. Joining twice is harmless.
     */
    @Transactional
    public Group join(CurrentUser user, String token) {
        UUID groupId = groupOf(token);
        lockGroup(groupId);
        if (!isMember(user, groupId)) {
            int claimed = jdbc.sql("""
                            update group_members set user_id = :userId, joined_at = now()
                            where group_id = :groupId and user_id is null and email = :email and left_at is null
                            """)
                    .param("userId", user.id()).param("groupId", groupId).param("email", user.email())
                    .update();
            if (claimed == 0) {
                jdbc.sql("""
                                insert into group_members (id, group_id, user_id, display_name, role, added_by, joined_at)
                                values (:id, :groupId, :userId, :name, 'MEMBER', :userId, now())
                                """)
                        .param("id", Uuid7.next()).param("groupId", groupId).param("userId", user.id())
                        .param("name", truncate(user.displayName()))
                        .update();
            }
            history.record(groupId, user, GroupHistory.Action.MEMBER_JOINED, GroupHistory.details("member", user.displayName(), "via", "link"));
            // Everyone already in the group hears about it.
            String group = jdbc.sql("select name from groups where id = :id").param("id", groupId).query(String.class).single();
            jdbc.sql("select user_id from group_members where group_id = :groupId and user_id is not null and user_id <> :me and left_at is null")
                    .param("groupId", groupId).param("me", user.id()).query(UUID.class).list()
                    .forEach(other -> inbox.notify(other, dev.payledger.core.notify.Inbox.Kind.GROUP_MEMBER,
                            user.displayName() + " joined " + group, null, "/groups/" + groupId, user.id()));
        }
        return get(user, groupId);
    }

    /** A new account takes over the placeholders invited with its (verified) email. */
    public void claimInvites(UUID userId, String email) {
        record Claimed(UUID groupId, String displayName) {
        }
        jdbc.sql("""
                        update group_members m set user_id = :userId, joined_at = now()
                        where m.user_id is null and m.email = :email and m.left_at is null
                          and not exists (select 1 from group_members x where x.group_id = m.group_id and x.user_id = :userId and x.left_at is null)
                        returning m.group_id, m.display_name
                        """)
                .param("userId", userId).param("email", email)
                .query(Claimed.class).list()
                .forEach(c -> history.record(c.groupId(), userId, c.displayName(), GroupHistory.Action.MEMBER_JOINED,
                        GroupHistory.details("member", c.displayName(), "via", "email")));
    }

    // ---------------------------------------------------------------- access

    /** The signed-in user's membership. */
    public record Me(UUID id, String role) {
    }

    /** 404 unless the user is a member: ids of other people's groups leak nothing. */
    public Me member(CurrentUser user, UUID groupId) {
        return jdbc.sql("select id, role from group_members where group_id = :groupId and user_id = :userId and left_at is null")
                .param("groupId", groupId).param("userId", user.id())
                .query(Me.class).optional()
                .orElseThrow(() -> new NotFoundException("group"));
    }

    private boolean isMember(CurrentUser user, UUID groupId) {
        return jdbc.sql("select exists (select 1 from group_members where group_id = :groupId and user_id = :userId and left_at is null)")
                .param("groupId", groupId).param("userId", user.id()).query(Boolean.class).single();
    }

    private UUID groupOf(String token) {
        return jdbc.sql("""
                        select l.group_id from group_links l join groups g on g.id = l.group_id
                        where l.token = :token and l.revoked_at is null and g.archived_at is null
                        """)
                .param("token", token).query(UUID.class).optional()
                .orElseThrow(InvalidLinkException::new);
    }

    /** Serialises changes to a group's members and link. */
    private void lockGroup(UUID groupId) {
        jdbc.sql("select id from groups where id = :id for update").param("id", groupId).query(UUID.class).single();
    }

    private String newLink(CurrentUser user, UUID groupId) {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("insert into group_links (token, group_id, created_by) values (:token, :groupId, :userId)")
                .param("token", token).param("groupId", groupId).param("userId", user.id())
                .update();
        return token;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String truncate(String name) {
        return name.length() > 60 ? name.substring(0, 60) : name;
    }

    static class MemberExistsException extends ApiException {
        MemberExistsException() {
            super(HttpStatus.CONFLICT, "member-exists", "someone with that email is already in the group");
        }
    }

    static class MemberHasBalanceException extends ApiException {
        MemberHasBalanceException() {
            super(HttpStatus.CONFLICT, "member-has-balance", "settle up first: they still owe or are owed money in this group");
        }
    }

    static class InvalidLinkException extends ApiException {
        InvalidLinkException() {
            super(HttpStatus.NOT_FOUND, "invalid-link", "this invite link doesn't work any more; ask for a new one");
        }
    }
}
