package dev.payledger.core.money;

import dev.payledger.core.common.ApiException;
import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.common.NotFoundException;
import dev.payledger.core.common.Uuid7;
import dev.payledger.core.common.VersionConflictException;
import dev.payledger.core.space.SpaceAccess;
import dev.payledger.core.space.SpaceAccess.Space;
import dev.payledger.core.user.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Commitments and their due dates. Paying one records an ordinary transaction (so balances, budgets
 * and reports see it like any other) and links it to the due date.
 */
@Service
public class CommitmentService {

    private static final String COLUMNS = """
            id, kind, name, amount, currency, account_id, category_id, to_account_id, every_months, first_due, ends_on, version
            """;

    private final JdbcClient jdbc;
    private final SpaceAccess access;
    private final AccountService accounts;
    private final CategoryService categories;
    private final TransactionService transactions;

    public CommitmentService(JdbcClient jdbc, SpaceAccess access, AccountService accounts, CategoryService categories,
                             TransactionService transactions) {
        this.jdbc = jdbc;
        this.access = access;
        this.accounts = accounts;
        this.categories = categories;
        this.transactions = transactions;
    }

    /**
     * One due date of a commitment.
     *
     * @param transactionId the payment, or null while unpaid
     * @param amount        what was paid, or the commitment's amount while unpaid
     */
    public record Due(UUID commitmentId, String kind, String name, LocalDate dueDate, long amount, String currency,
                      UUID transactionId, boolean paid) {

        Due(UUID commitmentId, String kind, String name, LocalDate dueDate, long amount, String currency, UUID transactionId) {
            this(commitmentId, kind, name, dueDate, amount, currency, transactionId, transactionId != null);
        }
    }

    @Transactional(readOnly = true)
    public List<Commitment> list(CurrentUser user, UUID spaceId) {
        access.read(user, spaceId);
        return jdbc.sql("select " + COLUMNS + " from commitments where space_id = :spaceId order by first_due, name")
                .param("spaceId", spaceId)
                .query(Commitment.class)
                .list();
    }

    @Transactional
    public Commitment create(CurrentUser user, UUID spaceId, CommitmentRequests.Fields f) {
        Space space = access.write(user, spaceId);
        String currency = validate(space, f);
        UUID id = Uuid7.next();
        jdbc.sql("""
                        insert into commitments (id, space_id, kind, name, amount, currency, account_id, category_id, to_account_id,
                                                 every_months, first_due, ends_on, created_by)
                        values (:id, :spaceId, :kind, :name, :amount, :currency, :accountId, :categoryId, :toAccountId,
                                :everyMonths, :firstDue, :endsOn, :userId)
                        """)
                .param("id", id).param("spaceId", spaceId).params(params(f)).param("currency", currency).param("userId", user.id())
                .update();
        return find(spaceId, id);
    }

    /** Changes apply to dates not paid yet; paid ones keep the transaction that paid them. */
    @Transactional
    public Commitment replace(CurrentUser user, UUID spaceId, UUID id, int version, CommitmentRequests.Fields f) {
        Space space = access.write(user, spaceId);
        find(spaceId, id);
        String currency = validate(space, f);
        int updated = jdbc.sql("""
                        update commitments set kind = :kind, name = :name, amount = :amount, currency = :currency,
                            account_id = :accountId, category_id = :categoryId, to_account_id = :toAccountId,
                            every_months = :everyMonths, first_due = :firstDue, ends_on = :endsOn,
                            version = version + 1, updated_at = now()
                        where id = :id and version = :version
                        """)
                .param("id", id).param("version", version).params(params(f)).param("currency", currency)
                .update();
        if (updated == 0) {
            throw new VersionConflictException("commitment");
        }
        return find(spaceId, id);
    }

    /** Its payments stay as ordinary transactions; only the schedule goes. */
    @Transactional
    public void delete(CurrentUser user, UUID spaceId, UUID id) {
        access.write(user, spaceId);
        if (jdbc.sql("delete from commitments where id = :id and space_id = :spaceId").param("id", id).param("spaceId", spaceId).update() == 0) {
            throw new NotFoundException("commitment");
        }
    }

    /** Every due date between the two dates, paid or not, oldest first. */
    @Transactional(readOnly = true)
    public List<Due> due(CurrentUser user, UUID spaceId, LocalDate from, LocalDate to) {
        access.read(user, spaceId);
        return due(spaceId, from, to);
    }

    /** For other services that already checked access. */
    public List<Due> due(UUID spaceId, LocalDate from, LocalDate to) {
        if (to.isBefore(from) || to.isAfter(from.plusYears(2))) {
            throw new InvalidRequestException("invalid-range", "to must be on or after from, and at most two years later");
        }
        List<Commitment> all = jdbc.sql("select " + COLUMNS + " from commitments where space_id = :spaceId and first_due <= :to")
                .param("spaceId", spaceId).param("to", to)
                .query(Commitment.class)
                .list();
        record Paid(UUID commitmentId, LocalDate dueDate, UUID transactionId, long amount) {
        }
        Map<String, Paid> paid = jdbc.sql("""
                        select p.commitment_id, p.due_date, p.transaction_id, t.amount
                        from commitment_payments p
                        join commitments c on c.id = p.commitment_id
                        join transactions t on t.id = p.transaction_id and t.deleted_at is null
                        where c.space_id = :spaceId and p.due_date between :from and :to
                        """)
                .param("spaceId", spaceId).param("from", from).param("to", to)
                .query(Paid.class)
                .list().stream()
                .collect(Collectors.toMap(p -> p.commitmentId() + "/" + p.dueDate(), Function.identity()));
        return all.stream()
                .flatMap(c -> c.dueDates(from, to).stream().map(date -> {
                    Paid p = paid.get(c.id() + "/" + date);
                    return new Due(c.id(), c.kind(), c.name(), date, p != null ? p.amount() : c.amount(), c.currency(),
                            p != null ? p.transactionId() : null);
                }))
                .sorted(Comparator.comparing(Due::dueDate).thenComparing(Due::name))
                .toList();
    }

    /**
     * Records the payment of one due date as a transaction: an expense for a BILL, a transfer for a SAVING.
     * A date can be paid once; deleting its transaction makes it payable again.
     */
    @Transactional
    public Due pay(CurrentUser user, UUID spaceId, UUID id, CommitmentRequests.Pay request) {
        access.write(user, spaceId);
        // The row lock makes two taps on "Paid" pay once: the second waits, then sees the first.
        Commitment c = jdbc.sql("select " + COLUMNS + " from commitments where id = :id and space_id = :spaceId for update")
                .param("id", id).param("spaceId", spaceId)
                .query(Commitment.class)
                .optional()
                .orElseThrow(() -> new NotFoundException("commitment"));
        if (!c.isDueOn(request.dueDate())) {
            throw new InvalidRequestException("not-a-due-date", c.name() + " isn't due on " + request.dueDate());
        }
        UUID existing = jdbc.sql("""
                        select p.transaction_id from commitment_payments p
                        join transactions t on t.id = p.transaction_id and t.deleted_at is null
                        where p.commitment_id = :id and p.due_date = :dueDate
                        """)
                .param("id", id).param("dueDate", request.dueDate())
                .query(UUID.class)
                .optional().orElse(null);
        if (existing != null) {
            throw new AlreadyPaidException(c.name(), existing);
        }
        boolean bill = c.kind().equals("BILL");
        long amount = Objects.requireNonNullElse(request.amount(), c.amount());
        LocalDate on = Objects.requireNonNullElse(request.occurredOn(), request.dueDate());
        Transaction t = transactions.create(user, spaceId, new TransactionRequests.Create(
                null, bill ? "EXPENSE" : "TRANSFER", amount, c.accountId(), bill ? null : c.toAccountId(),
                bill ? c.categoryId() : null, on, c.name(), null, List.of())).transaction();
        jdbc.sql("""
                        insert into commitment_payments (commitment_id, due_date, transaction_id) values (:id, :dueDate, :txn)
                        on conflict (commitment_id, due_date) do update set transaction_id = excluded.transaction_id, created_at = now()
                        """)
                .param("id", id).param("dueDate", request.dueDate()).param("txn", t.id())
                .update();
        return new Due(c.id(), c.kind(), c.name(), request.dueDate(), t.amount(), t.currency(), t.id());
    }

    private Commitment find(UUID spaceId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from commitments where id = :id and space_id = :spaceId")
                .param("id", id).param("spaceId", spaceId)
                .query(Commitment.class)
                .optional()
                .orElseThrow(() -> new NotFoundException("commitment"));
    }

    /** The accounts and category must exist in this space and fit the kind; returns the paying account's currency. */
    private String validate(Space space, CommitmentRequests.Fields f) {
        MoneyAccount from = accounts.find(space.id(), f.accountId(), false);
        if (from.archived()) {
            throw new InvalidRequestException("account-archived", from.name() + " is archived");
        }
        if (f.kind().equals("BILL")) {
            if (f.categoryId() == null || f.toAccountId() != null) {
                throw new InvalidRequestException("invalid-commitment", "a bill needs a categoryId and no toAccountId");
            }
            categories.require(space.id(), f.categoryId(), "EXPENSE");
        } else {
            if (f.toAccountId() == null || f.categoryId() != null || f.toAccountId().equals(f.accountId())) {
                throw new InvalidRequestException("invalid-commitment", "a saving needs another toAccountId and no categoryId");
            }
            MoneyAccount to = accounts.find(space.id(), f.toAccountId(), false);
            if (!to.currency().equals(from.currency())) {
                throw new InvalidRequestException("currency-mismatch", "both accounts need the same currency");
            }
        }
        if (f.endsOn() != null && f.endsOn().isBefore(f.firstDue())) {
            throw new InvalidRequestException("invalid-commitment", "endsOn is before firstDue");
        }
        return from.currency();
    }

    private static Map<String, Object> params(CommitmentRequests.Fields f) {
        var p = new java.util.HashMap<String, Object>();
        p.put("kind", f.kind());
        p.put("name", f.name().strip());
        p.put("amount", f.amount());
        p.put("accountId", f.accountId());
        p.put("categoryId", f.categoryId());
        p.put("toAccountId", f.toAccountId());
        p.put("everyMonths", f.everyMonths());
        p.put("firstDue", f.firstDue());
        p.put("endsOn", f.endsOn());
        return p;
    }

    static class AlreadyPaidException extends ApiException {
        AlreadyPaidException(String name, UUID transactionId) {
            super(HttpStatus.CONFLICT, "already-paid", name + " is already paid for this date (transaction " + transactionId + ")");
        }
    }
}
