package dev.payledger.core.group;

import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.common.NotFoundException;
import dev.payledger.core.user.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Everyone you share money with, across every group, and one-to-one loans (udhaar). A person is a registered user,
 * else an email, else a name in one group. What you and they owe each other comes from each group's fewest payments,
 * so it matches what "Settle up" asks for. A loan lives in a group of two marked direct (V22), which gives it
 * balances, settling up, history and your book for free.
 */
@Service
public class PeopleService {

    private final JdbcClient jdbc;
    private final GroupService groups;
    private final GroupExpenseService expenses;

    PeopleService(JdbcClient jdbc, GroupService groups, GroupExpenseService expenses) {
        this.jdbc = jdbc;
        this.groups = groups;
        this.expenses = expenses;
    }

    /** @param net paise from your side: positive, they owe you; negative, you owe them */
    public record Shared(UUID groupId, String name, String emoji, boolean direct, String currency, UUID memberId, long net) {
    }

    /**
     * @param key        stable id for this person: "u:" user, "e:" email or "m:" a group member
     * @param net        in INR across groups (other currencies are listed per group only)
     * @param directId   your one-to-one ledger with them, once there is one
     * @param nextDue    the earliest date a loan between you should be paid back, while there's something to pay
     * @param upiId      where to pay them, if they've set it
     */
    public record Person(String key, String name, String email, boolean registered, long net, List<Shared> groups, UUID directId,
                         LocalDate nextDue, LocalDate lastActivity, String upiId) {
    }

    public record Totals(long owedToYou, long youOwe) {
    }

    public record People(Totals totals, List<Person> people) {
    }

    private record Membership(UUID groupId, String name, String emoji, boolean direct, String currency, UUID me) {
    }

    private record Member(UUID id, UUID groupId, UUID userId, String email, String name, boolean left, String upiId) {
    }

    @Transactional(readOnly = true)
    public People list(CurrentUser user) {
        List<Membership> mine = jdbc.sql("""
                        select g.id as group_id, g.name, g.emoji, g.direct, g.currency, m.id as me
                        from group_members m join groups g on g.id = m.group_id where m.user_id = :u and m.left_at is null
                        """).param("u", user.id()).query(Membership.class).list();
        if (mine.isEmpty()) {
            return new People(new Totals(0, 0), List.of());
        }
        List<UUID> ids = mine.stream().map(Membership::groupId).toList();
        List<Member> members = jdbc.sql("""
                        select m.id, m.group_id, m.user_id, coalesce(u.email, m.email) as email, m.display_name as name,
                               m.left_at is not null as left, u.upi_id
                        from group_members m left join users u on u.id = m.user_id where m.group_id in (:ids)
                        """).param("ids", ids).query(Member.class).list();
        Map<UUID, Long> balance = balances(ids);
        Map<UUID, LocalDate> last = new HashMap<>();
        jdbc.sql("""
                        select group_id, max(d) as last from (
                          select group_id, occurred_on as d from group_expenses where group_id in (:ids) and deleted_at is null
                          union all select group_id, occurred_on from group_settlements where group_id in (:ids) and deleted_at is null) x
                        group by group_id
                        """).param("ids", ids).query((rs, n) -> last.put(rs.getObject("group_id", UUID.class), rs.getObject("last", LocalDate.class))).list();
        Map<UUID, LocalDate> due = new HashMap<>();
        jdbc.sql("""
                        select group_id, min(due_on) as due from group_expenses
                        where group_id in (:ids) and loan and deleted_at is null and due_on is not null group by group_id
                        """).param("ids", ids).query((rs, n) -> due.put(rs.getObject("group_id", UUID.class), rs.getObject("due", LocalDate.class))).list();

        Map<String, Person> people = new LinkedHashMap<>();
        for (Membership g : mine) {
            List<Member> in = members.stream().filter(m -> m.groupId().equals(g.groupId())).toList();
            Map<UUID, Long> groupBalance = new LinkedHashMap<>();
            in.forEach(m -> groupBalance.put(m.id(), balance.getOrDefault(m.id(), 0L)));
            Map<UUID, Long> withMe = new HashMap<>();
            for (Splits.Payment p : Splits.simplify(groupBalance)) {
                if (p.from().equals(g.me())) {
                    withMe.merge(p.to(), -p.amount(), Long::sum);
                } else if (p.to().equals(g.me())) {
                    withMe.merge(p.from(), p.amount(), Long::sum);
                }
            }
            for (Member m : in) {
                if (m.id().equals(g.me()) || (user.id().equals(m.userId()))) {
                    continue;
                }
                long net = withMe.getOrDefault(m.id(), 0L);
                if (m.left() && net == 0) {
                    continue;
                }
                String key = m.userId() != null ? "u:" + m.userId() : m.email() != null ? "e:" + m.email().toLowerCase(Locale.ROOT) : "m:" + m.id();
                Person was = people.get(key);
                Shared shared = new Shared(g.groupId(), g.name(), g.emoji(), g.direct(), g.currency(), m.id(), net);
                List<Shared> list = new ArrayList<>(was == null ? List.of() : was.groups());
                list.add(shared);
                LocalDate lastHere = last.get(g.groupId());
                LocalDate lastAll = was == null ? lastHere : max(was.lastActivity(), lastHere);
                LocalDate dueHere = g.direct() && net != 0 ? due.get(g.groupId()) : null;
                LocalDate nextDue = was == null ? dueHere : min(was.nextDue(), dueHere);
                long total = (was == null ? 0 : was.net()) + ("INR".equals(g.currency()) ? net : 0);
                UUID direct = g.direct() ? g.groupId() : was == null ? null : was.directId();
                String name = was != null && !g.direct() ? was.name() : m.name();
                people.put(key, new Person(key, name, m.email(), m.userId() != null, total, list, direct, nextDue, lastAll,
                        m.upiId() != null ? m.upiId() : was == null ? null : was.upiId()));
            }
        }
        List<Person> sorted = people.values().stream()
                .sorted(Comparator.comparing((Person p) -> p.net() == 0).thenComparing(p -> -Math.abs(p.net())).thenComparing(Person::name))
                .toList();
        long owed = sorted.stream().mapToLong(p -> Math.max(0, p.net())).sum();
        long owe = sorted.stream().mapToLong(p -> Math.max(0, -p.net())).sum();
        return new People(new Totals(owed, owe), sorted);
    }

    public enum Direction { LENT, BORROWED }

    /**
     * @param personKey  someone from {@link #list}; or else a new person by name (and optionally email)
     * @param accountId  your account the money left (lent) or arrived in (borrowed)
     */
    public record Loan(UUID id, String personKey, String name, String email, Direction direction, long amount, LocalDate occurredOn,
                       LocalDate dueOn, String note, UUID accountId) {
    }

    /** Records money lent or borrowed in your one-to-one ledger with that person, making it the first time. */
    @Transactional
    public GroupExpenseService.Expense lend(CurrentUser user, Loan loan) {
        if (loan.amount() <= 0 || loan.amount() > GroupExpenseRequests.MAX_AMOUNT) {
            throw new InvalidRequestException("invalid-amount", "enter the amount");
        }
        if (loan.dueOn() != null && loan.dueOn().isBefore(loan.occurredOn())) {
            throw new InvalidRequestException("invalid-due", "the date to pay back can't be before the loan");
        }
        Target who = target(user, loan);
        UUID group = direct(user, who);
        record Ids(UUID me, UUID them) {
        }
        Ids ids = jdbc.sql("""
                        select (select id from group_members where group_id = :g and user_id = :u and left_at is null) as me,
                               (select id from group_members where group_id = :g and (user_id is null or user_id <> :u) and left_at is null limit 1) as them
                        """).param("g", group).param("u", user.id()).query(Ids.class).single();
        boolean lent = loan.direction() == Direction.LENT;
        String note = loan.note() == null || loan.note().isBlank() ? null : loan.note().strip();
        String description = note != null ? note : (lent ? "Lent to " : "Borrowed from ") + who.name();
        UUID payer = lent ? ids.me() : ids.them();
        UUID owes = lent ? ids.them() : ids.me();
        return expenses.create(user, group, new GroupExpenseRequests.Create(loan.id(), description.length() > 120 ? description.substring(0, 120) : description,
                loan.amount(), loan.occurredOn(), "Lending",
                List.of(new GroupExpenseRequests.PartIn(payer, loan.amount())), Splits.Type.EXACT,
                List.of(new GroupExpenseRequests.PartIn(owes, loan.amount())), loan.accountId(), true, loan.dueOn()));
    }

    private record Target(UUID userId, String email, String name) {
    }

    private Target target(CurrentUser user, Loan loan) {
        String key = loan.personKey();
        if (key == null || key.isBlank()) {
            if (loan.name() == null || loan.name().isBlank()) {
                throw new InvalidRequestException("invalid-person", "who is it with?");
            }
            String email = loan.email() == null || loan.email().isBlank() ? null : loan.email().strip().toLowerCase(Locale.ROOT);
            if (email != null && email.equalsIgnoreCase(user.email())) {
                throw new InvalidRequestException("invalid-person", "that's you");
            }
            UUID userId = email == null ? null : jdbc.sql("select id from users where lower(email) = :e").param("e", email).query(UUID.class).optional().orElse(null);
            return new Target(userId, email, loan.name().strip());
        }
        // Someone you already share a group with: only then is their name or account yours to use.
        Person p = list(user).people().stream().filter(x -> x.key().equals(key)).findFirst().orElseThrow(() -> new NotFoundException("person"));
        UUID userId = key.startsWith("u:") ? UUID.fromString(key.substring(2)) : null;
        return new Target(userId, p.email(), p.name());
    }

    /** Your group of two with this person: found by their account, email, or (for a name only) the name. */
    private UUID direct(CurrentUser user, Target who) {
        UUID found = jdbc.sql("""
                        select g.id from groups g
                        join group_members me on me.group_id = g.id and me.user_id = :u and me.left_at is null
                        join group_members o on o.group_id = g.id and o.id <> me.id and o.left_at is null
                        left join users ou on ou.id = o.user_id
                        where g.direct and (
                          (cast(:them as uuid) is not null and o.user_id = :them)
                          or (cast(:email as text) is not null and lower(coalesce(ou.email, o.email)) = :email)
                          or (cast(:them as uuid) is null and cast(:email as text) is null and o.user_id is null and o.email is null
                              and lower(o.display_name) = lower(:name)))
                        order by g.created_at limit 1
                        """).param("u", user.id()).param("them", who.userId()).param("email", who.email()).param("name", who.name())
                .query(UUID.class).optional().orElse(null);
        if (found != null) {
            return found;
        }
        GroupService.Group g = groups.create(user, new GroupRequests.Create(who.name().length() > 60 ? who.name().substring(0, 60) : who.name(), "🤝", null));
        jdbc.sql("update groups set direct = true where id = :g").param("g", g.id()).update();
        groups.addMember(user, g.id(), new GroupRequests.AddMember(who.name().length() > 60 ? who.name().substring(0, 60) : who.name(), who.email()));
        return g.id();
    }

    /** Each member's balance from the group entries: positive, they owe the group; negative, they're owed. */
    private Map<UUID, Long> balances(List<UUID> groupIds) {
        Map<UUID, Long> out = new HashMap<>();
        jdbc.sql("""
                        select m.id,
                               coalesce((select sum(p.share - p.paid) from group_expense_parts p join group_expenses x on x.id = p.expense_id and x.deleted_at is null
                                         where p.member_id = m.id), 0)
                                 - coalesce((select sum(s.amount) from group_settlements s where s.from_member = m.id and s.deleted_at is null), 0)
                                 + coalesce((select sum(s.amount) from group_settlements s where s.to_member = m.id and s.deleted_at is null), 0) as net
                        from group_members m where m.group_id in (:ids)
                        """).param("ids", groupIds).query((rs, n) -> out.put(rs.getObject("id", UUID.class), rs.getLong("net"))).list();
        return out;
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a == null ? b : b == null ? a : a.isBefore(b) ? a : b;
    }
}
