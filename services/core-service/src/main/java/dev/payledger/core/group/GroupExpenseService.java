package dev.payledger.core.group;

import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.common.NotFoundException;
import dev.payledger.core.common.Uuid7;
import dev.payledger.core.common.VersionConflictException;
import dev.payledger.core.ledger.LedgerClient;
import dev.payledger.core.ledger.LedgerCommands;
import dev.payledger.core.ledger.LedgerCommands.Book;
import dev.payledger.core.ledger.LedgerCommands.Leg;
import dev.payledger.core.user.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Shared expenses, settlements and balances of a group (docs/adr/0007-groups-and-splitting.md). Each write
 * queues its ledger entry in the group's book in the same transaction: a member's account is debited their
 * share and credited what they paid, so a positive balance is what they owe. Edits and deletes are reversals.
 */
@Service
public class GroupExpenseService {

    private static final String MEMBER = "member:";

    private final JdbcClient jdbc;
    private final dev.payledger.core.notify.Inbox inbox;
    private final GroupService groups;
    private final LedgerCommands ledger;
    private final LedgerClient ledgerReads;
    private final GroupHistory history;

    public GroupExpenseService(JdbcClient jdbc, GroupService groups, LedgerCommands ledger, LedgerClient ledgerReads,
                               dev.payledger.core.notify.Inbox inbox, GroupHistory history) {
        this.history = history;
        this.jdbc = jdbc;
        this.groups = groups;
        this.ledger = ledger;
        this.ledgerReads = ledgerReads;
        this.inbox = inbox;
    }

    public record Part(UUID memberId, long paid, long share, Long input) {
    }

    public record Expense(UUID id, String description, long amount, String currency, LocalDate occurredOn, String category,
                          Splits.Type split, int version, List<Part> parts) {
    }

    public record Settlement(UUID id, UUID fromMemberId, UUID toMemberId, long amount, String currency, LocalDate occurredOn, String note) {
    }

    public record Activity(List<Expense> expenses, List<Settlement> settlements) {
    }

    /**
     * @param balance  positive: owes the group; negative: is owed
     * @param payments the fewest payments that settle everyone
     */
    public record Balances(boolean known, String currency, Map<UUID, Long> balance, List<Splits.Payment> payments) {
    }

    /**
     * A group's story in numbers: what was spent, who paid and whose share it was, by label and by month, and the
     * biggest expenses. Everything live, not just the latest 200 the activity list shows. Amounts are paise.
     */
    public record Insights(String currency, long spent, int expenses, long settled, long yourPaid, long yourShare,
                           List<MemberTotal> members, List<LabelTotal> labels, List<MonthTotal> months, List<Biggest> biggest) {
        /** @param left this person has left the group; their history still counts */
        public record MemberTotal(UUID memberId, String name, boolean left, long paid, long share, int expenses) {
        }

        /** @param label null for expenses without one */
        public record LabelTotal(String label, long amount, int count) {
        }

        /** @param month yyyy-mm */
        public record MonthTotal(String month, long amount, int count) {
        }

        public record Biggest(UUID id, String description, long amount, LocalDate occurredOn) {
        }
    }

    @Transactional(readOnly = true)
    public Insights insights(CurrentUser user, UUID groupId) {
        UUID me = groups.member(user, groupId).id();
        String currency = jdbc.sql("select currency from groups where id = :id").param("id", groupId).query(String.class).single();
        var members = jdbc.sql("""
                        select m.id as member_id, m.display_name as name, m.left_at is not null as "left",
                               coalesce(sum(p.paid), 0) as paid, coalesce(sum(p.share), 0) as share,
                               count(p.expense_id) filter (where p.share > 0 or p.paid > 0)::int as expenses
                        from group_members m
                        left join group_expense_parts p on p.member_id = m.id
                             and p.expense_id in (select id from group_expenses where group_id = :groupId and deleted_at is null)
                        where m.group_id = :groupId
                        group by m.id, m.display_name, m.left_at, m.created_at
                        having m.left_at is null or coalesce(sum(p.paid), 0) + coalesce(sum(p.share), 0) > 0
                        order by coalesce(sum(p.paid), 0) desc, m.created_at
                        """).param("groupId", groupId).query(Insights.MemberTotal.class).list();
        var labels = jdbc.sql("""
                        select nullif(trim(category), '') as label, sum(amount) as amount, count(*)::int as count
                        from group_expenses where group_id = :groupId and deleted_at is null
                        group by nullif(trim(category), '') order by sum(amount) desc
                        """).param("groupId", groupId).query(Insights.LabelTotal.class).list();
        var months = jdbc.sql("""
                        select to_char(occurred_on, 'YYYY-MM') as month, sum(amount) as amount, count(*)::int as count
                        from group_expenses where group_id = :groupId and deleted_at is null
                        group by 1 order by 1
                        """).param("groupId", groupId).query(Insights.MonthTotal.class).list();
        var biggest = jdbc.sql("""
                        select id, description, amount, occurred_on from group_expenses
                        where group_id = :groupId and deleted_at is null order by amount desc, occurred_on desc limit 5
                        """).param("groupId", groupId).query(Insights.Biggest.class).list();
        long settled = jdbc.sql("select coalesce(sum(amount), 0) from group_settlements where group_id = :groupId and deleted_at is null")
                .param("groupId", groupId).query(Long.class).single();
        var mine = members.stream().filter(m -> m.memberId().equals(me)).findFirst();
        return new Insights(currency, labels.stream().mapToLong(Insights.LabelTotal::amount).sum(),
                labels.stream().mapToInt(Insights.LabelTotal::count).sum(), settled,
                mine.map(Insights.MemberTotal::paid).orElse(0L), mine.map(Insights.MemberTotal::share).orElse(0L),
                members, labels, months, biggest);
    }

    @Transactional(readOnly = true)
    public Activity activity(CurrentUser user, UUID groupId) {
        groups.member(user, groupId);
        List<Settlement> settlements = jdbc.sql("""
                        select id, from_member as from_member_id, to_member as to_member_id, amount, currency, occurred_on, note from group_settlements
                        where group_id = :groupId and deleted_at is null order by occurred_on desc, created_at desc, id desc limit 200
                        """)
                .param("groupId", groupId).query(Settlement.class).list();
        return new Activity(expenses(groupId, null), settlements);
    }

    /** The group's live expenses, newest first (or just one), with their parts. */
    private List<Expense> expenses(UUID groupId, UUID only) {
        List<Expense> expenses = jdbc.sql("""
                        select id, description, amount, currency, occurred_on, category, split, version
                        from group_expenses where group_id = :groupId and deleted_at is null
                          and (cast(:only as uuid) is null or id = :only)
                        order by occurred_on desc, created_at desc, id desc limit 200
                        """)
                .param("groupId", groupId).param("only", only)
                .query((rs, n) -> new Expense(rs.getObject("id", UUID.class), rs.getString("description"), rs.getLong("amount"),
                        rs.getString("currency"), rs.getObject("occurred_on", LocalDate.class), rs.getString("category"),
                        Splits.Type.valueOf(rs.getString("split")), rs.getInt("version"), List.of()))
                .list();
        if (expenses.isEmpty()) {
            return expenses;
        }
        Map<UUID, List<Part>> parts = new HashMap<>();
        jdbc.sql("select expense_id, member_id, paid, share, input from group_expense_parts where expense_id in (:ids) order by position")
                .param("ids", expenses.stream().map(Expense::id).toList())
                .query((rs, n) -> parts.computeIfAbsent(rs.getObject("expense_id", UUID.class), k -> new ArrayList<>())
                        .add(new Part(rs.getObject("member_id", UUID.class), rs.getLong("paid"), rs.getLong("share"),
                                (Long) rs.getObject("input"))))
                .list();
        return expenses.stream().map(e -> new Expense(e.id(), e.description(), e.amount(), e.currency(), e.occurredOn(),
                e.category(), e.split(), e.version(), parts.getOrDefault(e.id(), List.of()))).toList();
    }

    /** A retry with the same client id returns the first. */
    @Transactional
    public Expense create(CurrentUser user, UUID groupId, GroupExpenseRequests.Create request) {
        groups.member(user, groupId);
        String currency = writable(groupId);
        UUID id = request.id() != null ? request.id() : Uuid7.next();
        Optional<UUID> existing = jdbc.sql("select group_id from group_expenses where id = :id").param("id", id).query(UUID.class).optional();
        if (existing.isPresent()) {
            if (!existing.get().equals(groupId)) {
                throw new InvalidRequestException("id-in-use", "this id belongs to another expense; use a new one");
            }
            return find(groupId, id);
        }
        Map<UUID, Long[]> parts = resolve(groupId, request.fields());
        int inserted = jdbc.sql("""
                        insert into group_expenses (id, group_id, description, amount, currency, occurred_on, category, split, created_by)
                        values (:id, :groupId, :description, :amount, :currency, :occurredOn, :category, :split, :userId)
                        on conflict (id) do nothing
                        """)
                .param("id", id).param("groupId", groupId).params(fields(request.fields())).param("currency", currency)
                .param("userId", user.id())
                .update();
        if (inserted == 0) {
            // The same client id raced itself; the first request's expense is committed by now.
            return find(groupId, id);
        }
        writeParts(id, parts, request.fields());
        int ledgerVersion = post(groupId, id, 1, request.fields(), parts) ? 1 : 0;
        jdbc.sql("update group_expenses set ledger_version = :v where id = :id").param("v", ledgerVersion).param("id", id).update();
        tell(user, groupId, parts, "added", request.fields().description(), request.fields().amount());
        history.record(groupId, user, GroupHistory.Action.EXPENSE_ADDED, snapshot(id));
        return find(groupId, id);
    }

    @Transactional
    public Expense replace(CurrentUser user, UUID groupId, UUID id, int version, GroupExpenseRequests.Fields f) {
        groups.member(user, groupId);
        writable(groupId);
        record Row(int version, int ledgerVersion, String description) {
        }
        Row before = jdbc.sql("""
                        select version, ledger_version, description from group_expenses
                        where id = :id and group_id = :groupId and deleted_at is null for update
                        """)
                .param("id", id).param("groupId", groupId).query(Row.class).optional()
                .orElseThrow(() -> new NotFoundException("expense"));
        if (before.version() != version) {
            throw new VersionConflictException("expense");
        }
        Map<UUID, Long[]> parts = resolve(groupId, f);
        Map<String, Object> was = snapshot(id);
        int next = version + 1;
        if (before.ledgerVersion() > 0) {
            ledger.reverse(Book.group(groupId), key(id, before.ledgerVersion()) + ":reversal", key(id, before.ledgerVersion()),
                    "Edited: " + before.description());
        }
        boolean posted = post(groupId, id, next, f, parts);
        jdbc.sql("""
                        update group_expenses set description = :description, amount = :amount, occurred_on = :occurredOn,
                            category = :category, split = :split, version = :version, ledger_version = :ledgerVersion, updated_at = now()
                        where id = :id
                        """)
                .param("id", id).params(fields(f)).param("version", next).param("ledgerVersion", posted ? next : 0)
                .update();
        jdbc.sql("delete from group_expense_parts where expense_id = :id").param("id", id).update();
        writeParts(id, parts, f);
        tell(user, groupId, parts, "edited", f.description(), f.amount());
        Map<String, Object> now = snapshot(id);
        // What changed, field by field, so the history can say "amount ₹900 → ₹1,200".
        List<Map<String, Object>> changes = new ArrayList<>();
        for (String field : List.of("description", "amount", "occurredOn", "category", "split", "paidBy", "shares")) {
            Object a = was.get(field);
            Object b = now.get(field);
            if (!java.util.Objects.equals(String.valueOf(a), String.valueOf(b))) {
                changes.add(GroupHistory.details("field", field, "before", a, "after", b));
            }
        }
        now.put("changes", changes);
        history.record(groupId, user, GroupHistory.Action.EXPENSE_EDITED, now);
        return find(groupId, id);
    }

    @Transactional
    public void delete(CurrentUser user, UUID groupId, UUID id, int version) {
        groups.member(user, groupId);
        record Row(int version, int ledgerVersion, String description) {
        }
        Row before = jdbc.sql("""
                        select version, ledger_version, description from group_expenses
                        where id = :id and group_id = :groupId and deleted_at is null for update
                        """)
                .param("id", id).param("groupId", groupId).query(Row.class).optional()
                .orElseThrow(() -> new NotFoundException("expense"));
        if (before.version() != version) {
            throw new VersionConflictException("expense");
        }
        Map<UUID, Long[]> was = new java.util.LinkedHashMap<>();
        jdbc.sql("select member_id, paid, share from group_expense_parts where expense_id = :id").param("id", id)
                .query((rs, i) -> was.put(rs.getObject("member_id", UUID.class), new Long[]{rs.getLong("paid"), rs.getLong("share")})).list();
        long amount = jdbc.sql("select amount from group_expenses where id = :id").param("id", id).query(Long.class).single();
        history.record(groupId, user, GroupHistory.Action.EXPENSE_DELETED, snapshot(id));
        jdbc.sql("update group_expenses set deleted_at = now(), version = version + 1, updated_at = now() where id = :id")
                .param("id", id).update();
        tell(user, groupId, was, "deleted", before.description(), amount);
        if (before.ledgerVersion() > 0) {
            ledger.reverse(Book.group(groupId), key(id, before.ledgerVersion()) + ":reversal", key(id, before.ledgerVersion()),
                    "Deleted: " + before.description());
        }
    }

    /** {@code from} paid {@code to}: their balances move towards zero. */
    @Transactional
    public Settlement settle(CurrentUser user, UUID groupId, GroupExpenseRequests.Settle request) {
        groups.member(user, groupId);
        String currency = writable(groupId);
        if (request.fromMemberId().equals(request.toMemberId())) {
            throw new InvalidRequestException("invalid-settlement", "a payment needs two different people");
        }
        Set<UUID> members = currentMembers(groupId);
        if (!members.contains(request.fromMemberId()) || !members.contains(request.toMemberId())) {
            throw new InvalidRequestException("invalid-settlement", "both people must be in the group");
        }
        UUID id = Uuid7.next();
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        jdbc.sql("""
                        insert into group_settlements (id, group_id, from_member, to_member, amount, currency, occurred_on, note, created_by)
                        values (:id, :groupId, :from, :to, :amount, :currency, :occurredOn, :note, :userId)
                        """)
                .param("id", id).param("groupId", groupId).param("from", request.fromMemberId()).param("to", request.toMemberId())
                .param("amount", request.amount()).param("currency", currency).param("occurredOn", request.occurredOn())
                .param("note", note).param("userId", user.id())
                .update();
        ledger.post(Book.group(groupId), "settle:" + id + ":v1", "SETTLEMENT", request.occurredOn(),
                note != null ? note : "Settled up", "settle:" + id, List.of(
                        new Leg(MEMBER + request.fromMemberId(), "ASSET", currency, -request.amount()),
                        new Leg(MEMBER + request.toMemberId(), "ASSET", currency, request.amount())));
        tellPayment(user, groupId, request.fromMemberId(), request.toMemberId(), request.amount(), false);
        history.record(groupId, user, GroupHistory.Action.SETTLEMENT_RECORDED, GroupHistory.details(
                "from", memberName(request.fromMemberId()), "to", memberName(request.toMemberId()), "amount", request.amount(),
                "currency", currency, "occurredOn", request.occurredOn().toString(), "note", note));
        return new Settlement(id, request.fromMemberId(), request.toMemberId(), request.amount(), currency, request.occurredOn(), note);
    }

    @Transactional
    public void deleteSettlement(CurrentUser user, UUID groupId, UUID id) {
        groups.member(user, groupId);
        int deleted = jdbc.sql("update group_settlements set deleted_at = now() where id = :id and group_id = :groupId and deleted_at is null")
                .param("id", id).param("groupId", groupId).update();
        if (deleted == 0) {
            throw new NotFoundException("settlement");
        }
        ledger.reverse(Book.group(groupId), "settle:" + id + ":v1:reversal", "settle:" + id + ":v1", "Deleted settlement");
        record Paid(UUID fromMember, UUID toMember, long amount) {
        }
        Paid p = jdbc.sql("select from_member, to_member, amount from group_settlements where id = :id").param("id", id).query(Paid.class).single();
        tellPayment(user, groupId, p.fromMember(), p.toMember(), p.amount(), true);
        history.record(groupId, user, GroupHistory.Action.SETTLEMENT_UNDONE,
                GroupHistory.details("from", memberName(p.fromMember()), "to", memberName(p.toMember()), "amount", p.amount()));
    }

    /** An expense as the history keeps it: what it was, who paid and whose share it was, by name. */
    private Map<String, Object> snapshot(UUID expenseId) {
        record Row(String description, long amount, String currency, LocalDate occurredOn, String category, String split) {
        }
        Row r = jdbc.sql("select description, amount, currency, occurred_on, category, split from group_expenses where id = :id")
                .param("id", expenseId).query(Row.class).single();
        record Part(String name, long paid, long share) {
        }
        List<Part> parts = jdbc.sql("""
                        select m.display_name as name, p.paid, p.share from group_expense_parts p join group_members m on m.id = p.member_id
                        where p.expense_id = :id order by p.position
                        """).param("id", expenseId).query(Part.class).list();
        Map<String, Object> d = GroupHistory.details("expenseId", expenseId.toString(), "description", r.description(), "amount", r.amount(),
                "currency", r.currency(), "occurredOn", r.occurredOn().toString(), "category", r.category(), "split", r.split());
        d.put("paidBy", parts.stream().filter(p -> p.paid() > 0).map(p -> GroupHistory.details("member", p.name(), "amount", p.paid())).toList());
        d.put("shares", parts.stream().filter(p -> p.share() > 0).map(p -> GroupHistory.details("member", p.name(), "amount", p.share())).toList());
        return d;
    }

    private String memberName(UUID memberId) {
        return jdbc.sql("select display_name from group_members where id = :id").param("id", memberId).query(String.class).optional().orElse("Someone");
    }

    @Transactional(readOnly = true)
    public List<GroupHistory.Event> history(CurrentUser user, UUID groupId) {
        groups.member(user, groupId);
        return history.list(groupId, user.id(), 500);
    }

    // ---------------------------------------------------------------- telling people

    /** Everyone with an account who is part of the expense (paid or shares), except whoever did it. */
    private void tell(CurrentUser user, UUID groupId, Map<UUID, Long[]> parts, String verb, String description, long amount) {
        String group = groupName(groupId);
        String who = user.displayName();
        for (var e : parts.entrySet()) {
            UUID to = userOf(e.getKey());
            if (to == null || to.equals(user.id())) {
                continue;
            }
            long paid = e.getValue()[0] == null ? 0 : e.getValue()[0];
            long share = e.getValue()[1] == null ? 0 : e.getValue()[1];
            String body = verb.equals("deleted") ? "It no longer counts in your balance."
                    : (paid > 0 ? "You paid " + money(paid) + "; your share is " + money(share) + "."
                    : "Your share is " + money(share) + ".");
            inbox.notify(to, dev.payledger.core.notify.Inbox.Kind.GROUP_EXPENSE,
                    who + " " + verb + " " + description + " (" + money(amount) + ") in " + group, body, "/groups/" + groupId, user.id());
        }
    }

    private void tellPayment(CurrentUser user, UUID groupId, UUID from, UUID to, long amount, boolean undone) {
        String group = groupName(groupId);
        String title = undone
                ? user.displayName() + " undid a payment of " + money(amount) + " in " + group
                : memberName(from) + " paid " + memberName(to) + " " + money(amount) + " in " + group;
        for (UUID member : List.of(from, to)) {
            UUID target = userOf(member);
            if (target != null && !target.equals(user.id())) {
                inbox.notify(target, dev.payledger.core.notify.Inbox.Kind.SETTLEMENT, title,
                        undone ? "Your balance is back to what it was before it." : "Recorded by " + user.displayName() + ".",
                        "/groups/" + groupId, user.id());
            }
        }
    }

    private UUID userOf(UUID memberId) {
        return jdbc.sql("select user_id from group_members where id = :id").param("id", memberId).query(UUID.class).optional().orElse(null);
    }

    private String groupName(UUID groupId) {
        return jdbc.sql("select name from groups where id = :id").param("id", groupId).query(String.class).single();
    }

    private static String money(long paise) {
        return dev.payledger.core.notify.Reminders.money(paise);
    }

    /** Each current member's balance from the group's ledger book, and the fewest payments that settle them. */
    @Transactional(readOnly = true)
    public Balances balances(CurrentUser user, UUID groupId) {
        groups.member(user, groupId);
        String currency = jdbc.sql("select currency from groups where id = :id").param("id", groupId).query(String.class).single();
        Optional<List<LedgerClient.Balance>> rows = ledgerReads.balances("group:" + groupId);
        if (rows.isEmpty()) {
            return new Balances(false, currency, null, List.of());
        }
        Map<UUID, Long> balance = new LinkedHashMap<>();
        currentMembers(groupId).forEach(m -> balance.put(m, 0L));
        rows.get().stream()
                .filter(b -> b.accountRef().startsWith(MEMBER) && b.currency().equals(currency))
                .forEach(b -> balance.put(UUID.fromString(b.accountRef().substring(MEMBER.length())), b.balance()));
        return new Balances(true, currency, balance, Splits.simplify(balance));
    }

    // ---------------------------------------------------------------- internals

    /** Member -> {paid, share, input}, in the order given, after checking everyone is in the group. */
    private Map<UUID, Long[]> resolve(UUID groupId, GroupExpenseRequests.Fields f) {
        Set<UUID> members = currentMembers(groupId);
        List<Splits.Part> shareParts = f.shares().stream()
                .map(p -> new Splits.Part(p.memberId(), p.value() == null ? 0 : p.value())).toList();
        List<Splits.Part> payerParts = f.paidBy().stream()
                .map(p -> new Splits.Part(p.memberId(), p.value() == null ? 0 : p.value())).toList();
        for (Splits.Part p : shareParts) {
            if (!members.contains(p.memberId())) {
                throw new InvalidRequestException("invalid-split", "everyone in the split must be in the group");
            }
            if ((f.split() == Splits.Type.SHARES || f.split() == Splits.Type.PERCENT) && p.value() > 10_000) {
                throw new InvalidRequestException("invalid-split", "a share or percentage is too large");
            }
        }
        for (Splits.Part p : payerParts) {
            if (!members.contains(p.memberId())) {
                throw new InvalidRequestException("invalid-payers", "everyone who paid must be in the group");
            }
        }
        Map<UUID, Long> shares = Splits.split(f.amount(), f.split(), shareParts);
        Map<UUID, Long> paid = Splits.payers(f.amount(), payerParts);
        Map<UUID, Long[]> parts = new LinkedHashMap<>();
        for (Splits.Part p : shareParts) {
            parts.put(p.memberId(), new Long[]{0L, shares.get(p.memberId()), f.split() == Splits.Type.EQUAL ? null : p.value()});
        }
        paid.forEach((m, amount) -> parts.computeIfAbsent(m, k -> new Long[]{0L, 0L, null})[0] = amount);
        return parts;
    }

    private void writeParts(UUID expenseId, Map<UUID, Long[]> parts, GroupExpenseRequests.Fields f) {
        int position = 0;
        for (var e : parts.entrySet()) {
            jdbc.sql("""
                            insert into group_expense_parts (expense_id, member_id, paid, share, input, position)
                            values (:expenseId, :memberId, :paid, :share, :input, :position)
                            """)
                    .param("expenseId", expenseId).param("memberId", e.getKey()).param("paid", e.getValue()[0])
                    .param("share", e.getValue()[1]).param("input", e.getValue()[2]).param("position", position++)
                    .update();
        }
    }

    /** Posts the expense's entry; false when nothing moves (one person paid exactly their own share). */
    private boolean post(UUID groupId, UUID id, int version, GroupExpenseRequests.Fields f, Map<UUID, Long[]> parts) {
        String currency = jdbc.sql("select currency from groups where id = :id").param("id", groupId).query(String.class).single();
        List<Leg> legs = parts.entrySet().stream()
                .filter(e -> e.getValue()[1] - e.getValue()[0] != 0)
                .map(e -> new Leg(MEMBER + e.getKey(), "ASSET", currency, e.getValue()[1] - e.getValue()[0]))
                .toList();
        if (legs.isEmpty()) {
            return false;
        }
        ledger.post(Book.group(groupId), key(id, version), "GROUP_EXPENSE", f.occurredOn(), f.description().strip(), "gexp:" + id, legs);
        return true;
    }

    private static String key(UUID id, int version) {
        return "gexp:" + id + ":v" + version;
    }

    private Set<UUID> currentMembers(UUID groupId) {
        return jdbc.sql("select id from group_members where group_id = :groupId and left_at is null")
                .param("groupId", groupId).query(UUID.class).list().stream().collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /** The group's currency; an archived group takes nothing new. */
    private String writable(UUID groupId) {
        record Row(String currency, boolean archived) {
        }
        Row g = jdbc.sql("select currency, archived_at is not null as archived from groups where id = :id")
                .param("id", groupId).query(Row.class).single();
        if (g.archived()) {
            throw new InvalidRequestException("group-archived", "the group is archived; unarchive it first");
        }
        return g.currency();
    }

    private Expense find(UUID groupId, UUID id) {
        return expenses(groupId, id).stream().findFirst().orElseThrow(() -> new NotFoundException("expense"));
    }

    private static Map<String, Object> fields(GroupExpenseRequests.Fields f) {
        Map<String, Object> p = new HashMap<>();
        p.put("description", f.description().strip());
        p.put("amount", f.amount());
        p.put("occurredOn", f.occurredOn());
        p.put("category", f.category() == null || f.category().isBlank() ? null : f.category().strip());
        p.put("split", f.split().name());
        return p;
    }
}
