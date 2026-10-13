package dev.payledger.core.money;

import dev.payledger.core.common.ApiException;
import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.common.NotFoundException;
import dev.payledger.core.common.Uuid7;
import dev.payledger.core.common.VersionConflictException;
import dev.payledger.core.ledger.LedgerCommands;
import dev.payledger.core.ledger.LedgerCommands.Leg;
import dev.payledger.core.space.SpaceAccess;
import dev.payledger.core.space.SpaceAccess.Space;
import dev.payledger.core.user.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Expenses, income and transfers.
 * <p>
 * Each write changes the row and queues the matching ledger commands in one transaction. The ledger
 * is append-only, so an edit that changes money (amount, accounts, category, date or kind) reverses the
 * live ledger entry and posts the new version; deleting reverses it. Idempotency keys are derived from
 * the transaction id and version ({@code txn:<id>:v<n>}), so a command delivered twice posts once.
 * <p>
 * Concurrent edits are serialised by a row lock, and the {@code version} the client sends must
 * still be current: the loser of a race gets 409 instead of silently overwriting the winner.
 */
@Service
public class TransactionService {

    static final int MAX_PAGE = 200;
    private static final String COLUMNS = """
            id, kind, amount, currency, account_id, to_account_id, category_id, occurred_on, description, note, tags,
            version, ledger_version, space_id, deleted_at is not null as deleted, created_at, updated_at
            """;

    private final JdbcClient jdbc;
    private final SpaceAccess access;
    private final AccountService accounts;
    private final CategoryService categories;
    private final LedgerCommands ledger;

    public TransactionService(JdbcClient jdbc, SpaceAccess access, AccountService accounts,
                              CategoryService categories, LedgerCommands ledger) {
        this.jdbc = jdbc;
        this.access = access;
        this.accounts = accounts;
        this.categories = categories;
        this.ledger = ledger;
    }

    /** @param created false when the client's id was already used for this same transaction (a retry) */
    public record Outcome(Transaction transaction, boolean created) {
    }

    /** One page, newest first. {@code nextCursor} is null on the last page. */
    public record Page(List<Transaction> items, String nextCursor) {
    }

    public record Filter(LocalDate from, LocalDate to, UUID accountId, UUID categoryId) {
    }

    @Transactional(readOnly = true)
    public Page list(CurrentUser user, UUID spaceId, Filter filter, String cursor, int limit) {
        access.read(user, spaceId);
        Cursor after = cursor == null ? null : Cursor.parse(cursor);
        List<Transaction> items = jdbc.sql("select " + COLUMNS + """
                        from transactions
                        where space_id = :spaceId and deleted_at is null
                          and (cast(:from as date) is null or occurred_on >= :from)
                          and (cast(:to as date) is null or occurred_on <= :to)
                          and (cast(:accountId as uuid) is null or account_id = :accountId or to_account_id = :accountId)
                          and (cast(:categoryId as uuid) is null or category_id = :categoryId)
                          and (cast(:afterDate as date) is null or (occurred_on, id) < (:afterDate, :afterId))
                        order by occurred_on desc, id desc
                        limit :limit
                        """)
                .param("spaceId", spaceId)
                .param("from", filter.from()).param("to", filter.to())
                .param("accountId", filter.accountId()).param("categoryId", filter.categoryId())
                .param("afterDate", after == null ? null : after.date())
                .param("afterId", after == null ? null : after.id())
                .param("limit", limit)
                .query(ROWS)
                .list().stream().map(Row::transaction).toList();
        String next = items.size() == limit ? new Cursor(items.getLast().occurredOn(), items.getLast().id()).encode() : null;
        return new Page(items, next);
    }

    @Transactional(readOnly = true)
    public Transaction get(CurrentUser user, UUID spaceId, UUID id) {
        access.read(user, spaceId);
        return find(spaceId, id, false).transaction();
    }

    @Transactional
    public Outcome create(CurrentUser user, UUID spaceId, TransactionRequests.Create request) {
        Space space = access.write(user, spaceId);
        if (request.id() != null) {
            var replayed = existing(space, request.id());
            if (replayed != null) {
                return new Outcome(replayed, false);
            }
        }
        Resolved r = resolve(space, request.fields(), null);
        UUID id = request.id() != null ? request.id() : Uuid7.next();
        int inserted = jdbc.sql("""
                        insert into transactions (id, space_id, kind, amount, currency, account_id, to_account_id, category_id,
                                                  occurred_on, description, note, tags, created_by)
                        values (:id, :spaceId, :kind, :amount, :currency, :accountId, :toAccountId, :categoryId,
                                :occurredOn, :description, :note, :tags, :userId)
                        on conflict (id) do nothing
                        """)
                .param("id", id).param("spaceId", spaceId)
                .params(r.params())
                .param("userId", user.id())
                .update();
        if (inserted == 0) {
            // The same client id raced itself: the other request committed first (the insert waited for it).
            var replayed = existing(space, id);
            if (replayed != null) {
                return new Outcome(replayed, false);
            }
            throw new IdInUseException();
        }
        postToLedger(space, id, 1, r);
        return new Outcome(find(spaceId, id, false).transaction(), true);
    }

    @Transactional
    public Transaction replace(CurrentUser user, UUID spaceId, UUID id, TransactionRequests.Replace request) {
        Space space = access.write(user, spaceId);
        Row before = find(spaceId, id, true);
        if (before.transaction().version() != request.version()) {
            throw new VersionConflictException("transaction");
        }
        Resolved r = resolve(space, request.fields(), before.transaction());
        int version = before.transaction().version() + 1;
        boolean moneyChanged = r.differsInMoney(before.transaction());
        jdbc.sql("""
                        update transactions set
                            kind = :kind, amount = :amount, currency = :currency, account_id = :accountId,
                            to_account_id = :toAccountId, category_id = :categoryId, occurred_on = :occurredOn,
                            description = :description, note = :note, tags = :tags,
                            version = :version, ledger_version = :ledgerVersion, updated_at = now()
                        where id = :id
                        """)
                .params(r.params())
                .param("version", version)
                .param("ledgerVersion", moneyChanged ? version : before.ledgerVersion())
                .param("id", id)
                .update();
        if (moneyChanged) {
            reverseInLedger(space, id, before.ledgerVersion(), "Edited: " + before.transaction().description());
            postToLedger(space, id, version, r);
        }
        return find(spaceId, id, false).transaction();
    }

    @Transactional
    public void delete(CurrentUser user, UUID spaceId, UUID id, int version) {
        Space space = access.write(user, spaceId);
        Row before = find(spaceId, id, true);
        if (before.transaction().version() != version) {
            throw new VersionConflictException("transaction");
        }
        jdbc.sql("update transactions set deleted_at = now(), version = version + 1, updated_at = now() where id = :id")
                .param("id", id)
                .update();
        reverseInLedger(space, id, before.ledgerVersion(), "Deleted: " + before.transaction().description());
    }

    /** Validates references against this space and builds the ledger legs. */
    private Resolved resolve(Space space, TransactionRequests.Fields f, Transaction previous) {
        MoneyAccount from = account(space, f.accountId(), previous);
        MoneyAccount to = null;
        Category category = null;
        if (f.kind().equals("TRANSFER")) {
            if (f.toAccountId() == null || f.categoryId() != null) {
                throw new InvalidRequestException("invalid-transfer", "a transfer needs toAccountId and no categoryId");
            }
            if (f.toAccountId().equals(f.accountId())) {
                throw new InvalidRequestException("invalid-transfer", "a transfer needs two different accounts");
            }
            to = account(space, f.toAccountId(), previous);
            if (!to.currency().equals(from.currency())) {
                throw new InvalidRequestException("currency-mismatch",
                        "both accounts of a transfer must use the same currency");
            }
        } else {
            if (f.categoryId() == null || f.toAccountId() != null) {
                throw new InvalidRequestException("invalid-" + f.kind().toLowerCase(),
                        "an " + f.kind().toLowerCase() + " needs a categoryId and no toAccountId");
            }
            category = categories.require(space.id(), f.categoryId(), f.kind());
            boolean unchanged = previous != null && f.categoryId().equals(previous.categoryId());
            if (category.hidden() && !unchanged) {
                throw new InvalidRequestException("category-hidden", "category " + category.name() + " is hidden");
            }
        }
        return new Resolved(f, from, to, category);
    }

    /** Archived accounts can't take new transactions, but an edit may keep the one it already had. */
    private MoneyAccount account(Space space, UUID accountId, Transaction previous) {
        boolean alreadyUsed = previous != null
                && (accountId.equals(previous.accountId()) || accountId.equals(previous.toAccountId()));
        return alreadyUsed ? accounts.find(space.id(), accountId, false) : accounts.requireOpen(space.id(), accountId);
    }

    private void postToLedger(Space space, UUID id, int version, Resolved r) {
        ledger.post(space, key(id, version), r.fields().kind(), r.fields().occurredOn(), r.fields().description(),
                Transaction.ledgerRef(id), r.legs());
    }

    private void reverseInLedger(Space space, UUID id, int ledgerVersion, String description) {
        ledger.reverse(space, key(id, ledgerVersion) + ":reversal", key(id, ledgerVersion), description);
    }

    static String key(UUID id, int version) {
        return Transaction.ledgerRef(id) + ":v" + version;
    }

    /** The transaction with this client-chosen id, if it is already in this space; 409 if it is somewhere else. */
    private Transaction existing(Space space, UUID id) {
        Row row = jdbc.sql("select " + COLUMNS + " from transactions where id = :id").param("id", id)
                .query(ROWS).optional().orElse(null);
        if (row == null) {
            return null;
        }
        if (!row.spaceId().equals(space.id()) || row.deleted()) {
            throw new IdInUseException();
        }
        return row.transaction();
    }

    private Row find(UUID spaceId, UUID id, boolean forUpdate) {
        return jdbc.sql("select " + COLUMNS + " from transactions where id = :id and space_id = :spaceId and deleted_at is null"
                        + (forUpdate ? " for update" : ""))
                .param("id", id).param("spaceId", spaceId)
                .query(ROWS)
                .optional()
                .orElseThrow(() -> new NotFoundException("transaction"));
    }

    private record Resolved(TransactionRequests.Fields fields, MoneyAccount from, MoneyAccount to, Category category) {

        List<Leg> legs() {
            long a = fields.amount();
            String currency = from.currency();
            Leg fromLeg = new Leg(from.ledgerRef(), from.kind().ledgerType(), currency, -a);
            return switch (fields.kind()) {
                case "EXPENSE" -> List.of(new Leg(category.ledgerRef(), "EXPENSE", currency, a), fromLeg);
                case "INCOME" -> List.of(new Leg(from.ledgerRef(), from.kind().ledgerType(), currency, a),
                        new Leg(category.ledgerRef(), "INCOME", currency, -a));
                case "TRANSFER" -> List.of(new Leg(to.ledgerRef(), to.kind().ledgerType(), currency, a), fromLeg);
                default -> throw new IllegalStateException(fields.kind());
            };
        }

        boolean differsInMoney(Transaction t) {
            return !fields.kind().equals(t.kind()) || fields.amount() != t.amount()
                    || !fields.accountId().equals(t.accountId()) || !Objects.equals(fields.toAccountId(), t.toAccountId())
                    || !Objects.equals(fields.categoryId(), t.categoryId()) || !fields.occurredOn().equals(t.occurredOn());
        }

        java.util.Map<String, Object> params() {
            var p = new java.util.HashMap<String, Object>();
            p.put("kind", fields.kind());
            p.put("amount", fields.amount());
            p.put("currency", from.currency());
            p.put("accountId", fields.accountId());
            p.put("toAccountId", fields.toAccountId());
            p.put("categoryId", fields.categoryId());
            p.put("occurredOn", fields.occurredOn());
            p.put("description", fields.description().strip());
            p.put("note", fields.note() == null || fields.note().isBlank() ? null : fields.note().strip());
            p.put("tags", fields.tags() == null ? new String[0]
                    : fields.tags().stream().map(String::strip).distinct().toArray(String[]::new));
            return p;
        }
    }

    private record Row(Transaction transaction, int ledgerVersion, UUID spaceId, boolean deleted) {
    }

    private static final RowMapper<Row> ROWS = (rs, n) -> new Row(new Transaction(
            rs.getObject("id", UUID.class), rs.getString("kind"), rs.getLong("amount"), rs.getString("currency"),
            rs.getObject("account_id", UUID.class), rs.getObject("to_account_id", UUID.class),
            rs.getObject("category_id", UUID.class), rs.getObject("occurred_on", LocalDate.class),
            rs.getString("description"), rs.getString("note"), tags(rs), rs.getInt("version"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant()),
            rs.getInt("ledger_version"), rs.getObject("space_id", UUID.class), rs.getBoolean("deleted"));

    private static List<String> tags(ResultSet rs) throws SQLException {
        Array array = rs.getArray("tags");
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    /** Keyset cursor "yyyy-mm-dd_uuid": stable while new transactions are added, unlike an offset. */
    record Cursor(LocalDate date, UUID id) {

        String encode() {
            return date + "_" + id;
        }

        static Cursor parse(String value) {
            try {
                int split = value.indexOf('_');
                return new Cursor(LocalDate.parse(value.substring(0, split)), UUID.fromString(value.substring(split + 1)));
            } catch (RuntimeException e) {
                throw new InvalidRequestException("invalid-cursor", "cursor is not one this API returned");
            }
        }
    }

    static class IdInUseException extends ApiException {
        IdInUseException() {
            super(HttpStatus.CONFLICT, "id-in-use", "this id belongs to another transaction; use a new one");
        }
    }
}
