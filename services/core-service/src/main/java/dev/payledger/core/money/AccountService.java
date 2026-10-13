package dev.payledger.core.money;

import dev.payledger.core.common.InvalidRequestException;
import dev.payledger.core.common.NotFoundException;
import dev.payledger.core.common.Uuid7;
import dev.payledger.core.ledger.LedgerCommands;
import dev.payledger.core.ledger.LedgerCommands.Leg;
import dev.payledger.core.space.SpaceAccess;
import dev.payledger.core.space.SpaceAccess.Space;
import dev.payledger.core.user.CurrentUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Money accounts. The opening balance is a ledger entry against {@code equity:opening-balances},
 * so the ledger balance of an account is always "opening balance + every transaction since".
 * Changing the opening balance reverses the old entry and posts a new one (never an update).
 */
@Service
public class AccountService {

    static final String OPENING_EQUITY = "equity:opening-balances";
    private static final String COLUMNS = """
            id, space_id, kind, name, currency, opening_balance, opening_date, archived_at is not null as archived, created_at
            """;

    private final JdbcClient jdbc;
    private final SpaceAccess access;
    private final LedgerCommands ledger;

    public AccountService(JdbcClient jdbc, SpaceAccess access, LedgerCommands ledger) {
        this.jdbc = jdbc;
        this.access = access;
        this.ledger = ledger;
    }

    @Transactional(readOnly = true)
    public List<MoneyAccount> list(CurrentUser user, UUID spaceId, boolean includeArchived) {
        access.read(user, spaceId);
        return jdbc.sql("select " + COLUMNS + """
                        from money_accounts
                        where space_id = :spaceId and (:includeArchived or archived_at is null)
                        order by archived_at is not null, created_at
                        """)
                .param("spaceId", spaceId)
                .param("includeArchived", includeArchived)
                .query(MoneyAccount.class)
                .list();
    }

    /** Every account of a space, archived ones too, for callers that already checked access. */
    List<MoneyAccount> all(UUID spaceId) {
        return jdbc.sql("select " + COLUMNS + " from money_accounts where space_id = :spaceId order by created_at")
                .param("spaceId", spaceId)
                .query(MoneyAccount.class)
                .list();
    }

    @Transactional(readOnly = true)
    public MoneyAccount get(CurrentUser user, UUID spaceId, UUID accountId) {
        access.read(user, spaceId);
        return find(spaceId, accountId, false);
    }

    @Transactional
    public MoneyAccount create(CurrentUser user, UUID spaceId, AccountRequests.Create request) {
        Space space = access.write(user, spaceId);
        UUID id = Uuid7.next();
        long opening = Objects.requireNonNullElse(request.openingBalance(), 0L);
        LocalDate openingDate = request.openingDate() != null ? request.openingDate() : today(user);
        String currency = request.currency() != null ? request.currency() : space.currency();
        jdbc.sql("""
                        insert into money_accounts (id, space_id, kind, name, currency, opening_balance, opening_date, created_by)
                        values (:id, :spaceId, :kind, :name, :currency, :opening, :openingDate, :userId)
                        """)
                .param("id", id).param("spaceId", spaceId).param("kind", request.kind().name())
                .param("name", request.name().strip()).param("currency", currency)
                .param("opening", opening).param("openingDate", openingDate).param("userId", user.id())
                .update();
        MoneyAccount account = find(spaceId, id, false);
        postOpening(space, account, 1);
        return account;
    }

    @Transactional
    public MoneyAccount update(CurrentUser user, UUID spaceId, UUID accountId, AccountRequests.Update request) {
        Space space = access.write(user, spaceId);
        MoneyAccount before = find(spaceId, accountId, true);
        long opening = Objects.requireNonNullElse(request.openingBalance(), before.openingBalance());
        LocalDate openingDate = Objects.requireNonNullElse(request.openingDate(), before.openingDate());
        boolean openingChanged = opening != before.openingBalance() || !openingDate.equals(before.openingDate());

        int openingVersion = jdbc.sql("""
                        update money_accounts set
                            name            = coalesce(:name, name),
                            opening_balance = :opening,
                            opening_date    = :openingDate,
                            opening_version = opening_version + case when :openingChanged then 1 else 0 end,
                            archived_at     = case when cast(:archived as boolean) is null then archived_at
                                                   when cast(:archived as boolean) then coalesce(archived_at, now())
                                                   else null end,
                            updated_at      = now()
                        where id = :id
                        returning opening_version
                        """)
                .param("name", request.name() == null ? null : request.name().strip())
                .param("opening", opening).param("openingDate", openingDate)
                .param("openingChanged", openingChanged).param("archived", request.archived())
                .param("id", accountId)
                .query(Integer.class)
                .single();

        MoneyAccount after = find(spaceId, accountId, false);
        if (openingChanged) {
            int previous = openingVersion - 1;
            if (before.openingBalance() != 0) {
                ledger.reverse(space, openingKey(accountId, previous) + ":reversal", openingKey(accountId, previous),
                        "Opening balance changed: " + after.name());
            }
            postOpening(space, after, openingVersion);
        }
        return after;
    }

    /** Locks the row when {@code forUpdate}: concurrent opening-balance edits then run one after the other. */
    MoneyAccount find(UUID spaceId, UUID accountId, boolean forUpdate) {
        return jdbc.sql("select " + COLUMNS + " from money_accounts where id = :id and space_id = :spaceId"
                        + (forUpdate ? " for update" : ""))
                .param("id", accountId).param("spaceId", spaceId)
                .query(MoneyAccount.class)
                .optional()
                .orElseThrow(() -> new NotFoundException("account"));
    }

    /** For transactions: the account must exist in this space and still be open. */
    MoneyAccount requireOpen(UUID spaceId, UUID accountId) {
        MoneyAccount account = find(spaceId, accountId, false);
        if (account.archived()) {
            throw new InvalidRequestException("account-archived", "account " + account.name() + " is archived");
        }
        return account;
    }

    private void postOpening(Space space, MoneyAccount account, int version) {
        if (account.openingBalance() == 0) {
            return;
        }
        long amount = account.kind().toLedger(account.openingBalance());
        ledger.post(space, openingKey(account.id(), version), "OPENING_BALANCE", account.openingDate(),
                "Opening balance: " + account.name(), account.ledgerRef(), List.of(
                        new Leg(account.ledgerRef(), account.kind().ledgerType(), account.currency(), amount),
                        new Leg(OPENING_EQUITY, "EQUITY", account.currency(), Math.negateExact(amount))));
    }

    static String openingKey(UUID accountId, int version) {
        return MoneyAccount.ledgerRef(accountId) + ":opening:v" + version;
    }

    LocalDate today(CurrentUser user) {
        String zone = jdbc.sql("select time_zone from users where id = :id").param("id", user.id()).query(String.class).single();
        return LocalDate.now(ZoneId.of(zone));
    }
}
