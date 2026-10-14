package dev.payledger.core.group;

import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.common.NotFoundException;
import dev.payledger.core.common.Uuid7;
import dev.payledger.core.money.Transaction;
import dev.payledger.core.money.TransactionService;
import dev.payledger.core.user.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Keeps each member's personal book in step with the group (product plan 2.3, V21):
 * <ul>
 *   <li>your share of an expense is your spending, in your category (matched by the group's label, else "Other"),
 *       paid from your "Friends owe me" account;</li>
 *   <li>what you paid moves from the account you paid from into "Friends owe me";</li>
 *   <li>a settlement moves money between that account and "Friends owe me".</li>
 * </ul>
 * So your bank shows the money gone, your budget counts only your share, and "Friends owe me" is what the group owes
 * you (negative: what you owe). Every booking is an ordinary transaction (tagged "group"), so the ledger, budgets and
 * reports see it; a change to the group entry deletes and re-adds them, keeping a category you chose yourself.
 * Members without an account, members who turned it off, and groups in another currency are left out.
 */
@Service
public class GroupBooking {

    static final String OWED_NAME = "Friends owe me";

    private final JdbcClient jdbc;
    private final TransactionService transactions;

    GroupBooking(JdbcClient jdbc, TransactionService transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** @param accountId where group payments come from unless chosen on the expense; null: the first bank account */
    public record Settings(boolean enabled, UUID accountId, UUID owedAccountId) {
    }

    private record Member(UUID userId, String subject, String email, String displayName, UUID spaceId, boolean enabled, UUID accountId) {
        CurrentUser asUser() {
            return new CurrentUser(userId, subject, email, displayName, spaceId);
        }
    }

    /** Books what's already in people's groups once, after V21 (and for anyone it didn't finish for). */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @Transactional
    public void catchUp() {
        List<UUID> users = jdbc.sql("""
                        select u.id from users u where not u.group_synced and u.disabled_at is null
                        and exists (select 1 from group_members m where m.user_id = u.id)
                        """).query(UUID.class).list();
        users.forEach(this::syncAll);
        jdbc.sql("update users set group_synced = true where not group_synced").update();
    }

    // ---------------------------------------------------------------- settings

    @Transactional(readOnly = true)
    public Settings settings(CurrentUser user) {
        record Row(boolean groupBudget, UUID groupAccountId) {
        }
        Row r = jdbc.sql("select group_budget, group_account_id from users where id = :u").param("u", user.id()).query(Row.class).single();
        UUID owed = jdbc.sql("select id from money_accounts where space_id = :s and kind = 'OWED' and archived_at is null")
                .param("s", user.personalSpaceId()).query(UUID.class).optional().orElse(null);
        return new Settings(r.groupBudget(), r.groupAccountId(), owed);
    }

    /** Turns booking on or off and sets the default account; turning it on books what's already in your groups. */
    @Transactional
    public Settings update(CurrentUser user, boolean enabled, UUID accountId) {
        if (accountId != null) {
            payable(user.personalSpaceId(), accountId);
        }
        jdbc.sql("update users set group_budget = :e, group_account_id = :a where id = :u")
                .param("e", enabled).param("a", accountId).param("u", user.id()).update();
        syncAll(user.id());
        return settings(user);
    }

    /** Books (or unbooks) every group entry you're part of: after turning it on, or to repair. */
    @Transactional
    public int syncAll(UUID userId) {
        List<UUID> expenses = jdbc.sql("""
                        select distinct e.id from group_expenses e join group_expense_parts p on p.expense_id = e.id
                        join group_members m on m.id = p.member_id where m.user_id = :u
                        """).param("u", userId).query(UUID.class).list();
        List<UUID> settlements = jdbc.sql("""
                        select distinct s.id from group_settlements s join group_members m on m.id in (s.from_member, s.to_member)
                        where m.user_id = :u
                        """).param("u", userId).query(UUID.class).list();
        expenses.forEach(id -> syncExpense(id, userId));
        settlements.forEach(id -> syncSettlement(id, userId));
        return expenses.size() + settlements.size();
    }

    /** Remembers the account you paid an expense from (or a settlement from or into). */
    void remember(UUID entryId, CurrentUser user, UUID accountId) {
        if (accountId == null) {
            return;
        }
        payable(user.personalSpaceId(), accountId);
        jdbc.sql("""
                        insert into group_entry_accounts (entry_id, user_id, account_id) values (:e, :u, :a)
                        on conflict (entry_id, user_id) do update set account_id = excluded.account_id
                        """).param("e", entryId).param("u", user.id()).param("a", accountId).update();
    }

    // ---------------------------------------------------------------- syncing

    /** Every member's book for this expense; {@code only} limits it to one user. */
    void syncExpense(UUID expenseId, UUID only) {
        record Expense(UUID groupId, String description, String currency, LocalDate occurredOn, String category, boolean deleted, String groupName) {
        }
        Expense e = jdbc.sql("""
                        select e.group_id, e.description, e.currency, e.occurred_on, e.category, e.deleted_at is not null as deleted, g.name as group_name
                        from group_expenses e join groups g on g.id = e.group_id where e.id = :id
                        """).param("id", expenseId).query(Expense.class).single();
        record Part(UUID userId, long paid, long share) {
        }
        Map<UUID, Part> parts = new HashMap<>();
        if (!e.deleted()) {
            jdbc.sql("""
                            select m.user_id, p.paid, p.share from group_expense_parts p join group_members m on m.id = p.member_id
                            where p.expense_id = :id and m.user_id is not null
                            """).param("id", expenseId).query(Part.class).list().forEach(p -> parts.put(p.userId(), p));
        }
        for (Member m : affected("gexp:" + expenseId, parts.keySet(), only)) {
            Part p = parts.get(m.userId());
            List<Wanted> wanted = new ArrayList<>();
            if (p != null && m.enabled() && sameCurrency(m, e.currency())) {
                UUID owed = owedAccount(m);
                String label = e.description() + " · " + e.groupName();
                if (p.share() > 0) {
                    wanted.add(new Wanted("gexp:" + expenseId + ":share", "EXPENSE", p.share(), owed, null, category(m, e.category()), e.occurredOn(), label));
                }
                if (p.paid() > 0) {
                    UUID from = accountFor(expenseId, m);
                    if (from != null) {
                        wanted.add(new Wanted("gexp:" + expenseId + ":paid", "TRANSFER", p.paid(), from, owed, null, e.occurredOn(), "Paid for " + label));
                    }
                }
            }
            apply(m, "gexp:" + expenseId, wanted);
        }
    }

    void syncSettlement(UUID settlementId, UUID only) {
        record Settlement(String currency, LocalDate occurredOn, boolean deleted, UUID fromUser, UUID toUser, long amount, String note,
                          String fromName, String toName, String groupName) {
        }
        Settlement s = jdbc.sql("""
                        select s.currency, s.occurred_on, s.deleted_at is not null as deleted, f.user_id as from_user, t.user_id as to_user, s.amount, s.note,
                               f.display_name as from_name, t.display_name as to_name, g.name as group_name
                        from group_settlements s join group_members f on f.id = s.from_member join group_members t on t.id = s.to_member
                        join groups g on g.id = s.group_id where s.id = :id
                        """).param("id", settlementId).query(Settlement.class).single();
        java.util.Set<UUID> involved = new java.util.HashSet<>();
        if (!s.deleted()) {
            if (s.fromUser() != null) involved.add(s.fromUser());
            if (s.toUser() != null) involved.add(s.toUser());
        }
        for (Member m : affected("gset:" + settlementId, involved, only)) {
            List<Wanted> wanted = new ArrayList<>();
            if (involved.contains(m.userId()) && m.enabled() && sameCurrency(m, s.currency())) {
                UUID owed = owedAccount(m);
                UUID account = accountFor(settlementId, m);
                if (account != null) {
                    boolean paying = m.userId().equals(s.fromUser());
                    String label = (paying ? "Paid " + s.toName() : s.fromName() + " paid you") + " · " + s.groupName();
                    wanted.add(paying
                            ? new Wanted("gset:" + settlementId + ":out", "TRANSFER", s.amount(), account, owed, null, s.occurredOn(), label)
                            : new Wanted("gset:" + settlementId + ":in", "TRANSFER", s.amount(), owed, account, null, s.occurredOn(), label));
                }
            }
            apply(m, "gset:" + settlementId, wanted);
        }
    }

    private record Wanted(String link, String kind, long amount, UUID accountId, UUID toAccountId, UUID categoryId, LocalDate on, String description) {
    }

    /** Makes the member's linked transactions for one entry exactly {@code wanted}: unchanged ones stay. */
    private void apply(Member m, String entry, List<Wanted> wanted) {
        record Have(UUID id, int version, String groupLink, String kind, long amount, UUID accountId, UUID toAccountId, UUID categoryId,
                    LocalDate occurredOn, String description) {
        }
        List<Have> have = jdbc.sql("""
                        select id, version, group_link, kind, amount, account_id, to_account_id, category_id, occurred_on, description
                        from transactions where space_id = :s and deleted_at is null and group_link like :entry || ':%'
                        """).param("s", m.spaceId()).param("entry", entry).query(Have.class).list();
        CurrentUser user = m.asUser();
        for (Have h : have) {
            Wanted w = wanted.stream().filter(x -> x.link().equals(h.groupLink())).findFirst().orElse(null);
            // A category you changed yourself is kept.
            boolean same = w != null && w.kind().equals(h.kind()) && w.amount() == h.amount() && w.accountId().equals(h.accountId())
                    && Objects.equals(w.toAccountId(), h.toAccountId()) && w.on().equals(h.occurredOn()) && w.description().equals(h.description());
            if (same) {
                wanted.remove(w);
                continue;
            }
            if (w != null && h.categoryId() != null && w.categoryId() != null) {
                wanted.set(wanted.indexOf(w), new Wanted(w.link(), w.kind(), w.amount(), w.accountId(), w.toAccountId(), h.categoryId(), w.on(), w.description()));
            }
            transactions.delete(user, m.spaceId(), h.id(), h.version());
        }
        for (Wanted w : wanted) {
            Transaction t = transactions.create(user, m.spaceId(), new dev.payledger.core.money.TransactionRequests.Create(
                    null, w.kind(), w.amount(), w.accountId(), w.toAccountId(), w.categoryId(), w.on(),
                    w.description().length() > 120 ? w.description().substring(0, 120) : w.description(), null, List.of("group"), null)).transaction();
            jdbc.sql("update transactions set group_link = :l where id = :id").param("l", w.link()).param("id", t.id()).update();
        }
    }

    /** Members to look at: those with a part now, plus anyone who has bookings for this entry (to remove them). */
    private List<Member> affected(String entry, java.util.Set<UUID> users, UUID only) {
        List<UUID> booked = jdbc.sql("""
                        select distinct s.created_by from transactions t join spaces s on s.id = t.space_id
                        where t.deleted_at is null and t.group_link like :entry || ':%' and s.kind = 'PERSONAL'
                        """).param("entry", entry).query(UUID.class).list();
        java.util.Set<UUID> all = new java.util.HashSet<>(users);
        all.addAll(booked);
        if (only != null) {
            all.retainAll(java.util.Set.of(only));
        }
        if (all.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select u.id as user_id, u.subject, u.email, u.display_name, s.id as space_id, u.group_budget as enabled, u.group_account_id as account_id
                        from users u join spaces s on s.created_by = u.id and s.kind = 'PERSONAL'
                        where u.id in (:ids) and u.disabled_at is null
                        """).param("ids", all).query(Member.class).list();
    }

    private boolean sameCurrency(Member m, String currency) {
        return currency.equals(jdbc.sql("select currency from spaces where id = :s").param("s", m.spaceId()).query(String.class).single());
    }

    /** The member's "Friends owe me" account, made the first time it's needed. */
    private UUID owedAccount(Member m) {
        return jdbc.sql("select id from money_accounts where space_id = :s and kind = 'OWED' and archived_at is null")
                .param("s", m.spaceId()).query(UUID.class).optional().orElseGet(() -> {
                    UUID id = Uuid7.next();
                    String currency = jdbc.sql("select currency from spaces where id = :s").param("s", m.spaceId()).query(String.class).single();
                    jdbc.sql("""
                                    insert into money_accounts (id, space_id, kind, name, currency, opening_balance, opening_date, created_by)
                                    values (:id, :s, 'OWED', :name, :currency, 0, :on, :u)
                                    """).param("id", id).param("s", m.spaceId()).param("name", OWED_NAME).param("currency", currency)
                            .param("on", LocalDate.now()).param("u", m.userId()).update();
                    return id;
                });
    }

    /** Where this member paid from: what they chose on the entry, else their default, else their first bank account. */
    private UUID accountFor(UUID entryId, Member m) {
        UUID chosen = jdbc.sql("""
                        select a.id from group_entry_accounts g join money_accounts a on a.id = g.account_id and a.archived_at is null
                        where g.entry_id = :e and g.user_id = :u
                        """).param("e", entryId).param("u", m.userId()).query(UUID.class).optional().orElse(null);
        if (chosen != null) {
            return chosen;
        }
        if (m.accountId() != null && jdbc.sql("select exists (select 1 from money_accounts where id = :a and archived_at is null)")
                .param("a", m.accountId()).query(Boolean.class).single()) {
            return m.accountId();
        }
        return jdbc.sql("""
                        select id from money_accounts where space_id = :s and archived_at is null and kind <> 'OWED'
                        order by kind = 'BANK' desc, created_at limit 1
                        """).param("s", m.spaceId()).query(UUID.class).optional().orElse(null);
    }

    /** The member's category with the group's label as its name (ignoring case), else "Other". */
    private UUID category(Member m, String label) {
        if (label != null && !label.isBlank()) {
            UUID match = jdbc.sql("""
                            select id from categories where space_id = :s and kind = 'EXPENSE' and not hidden and lower(name) = :n
                            order by parent_id nulls first limit 1
                            """).param("s", m.spaceId()).param("n", label.strip().toLowerCase(Locale.ROOT)).query(UUID.class).optional().orElse(null);
            if (match != null) {
                return match;
            }
        }
        return jdbc.sql("""
                        select id from categories where space_id = :s and kind = 'EXPENSE' and not hidden
                        order by name = 'Other' desc, parent_id nulls first, sort_order limit 1
                        """).param("s", m.spaceId()).query(UUID.class).optional()
                .orElseThrow(() -> new NotFoundException("category"));
    }

    private void payable(UUID spaceId, UUID accountId) {
        boolean ok = jdbc.sql("select exists (select 1 from money_accounts where id = :a and space_id = :s and archived_at is null and kind <> 'OWED')")
                .param("a", accountId).param("s", spaceId).query(Boolean.class).single();
        if (!ok) {
            throw new InvalidRequestException("invalid-account", "choose one of your own open accounts");
        }
    }
}
