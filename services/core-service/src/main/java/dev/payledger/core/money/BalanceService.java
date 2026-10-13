package dev.payledger.core.money;

import dev.payledger.core.common.ApiException;
import dev.payledger.core.ledger.LedgerClient;
import dev.payledger.core.space.SpaceAccess;
import dev.payledger.core.space.SpaceAccess.Space;
import dev.payledger.core.user.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Numbers that come from the ledger (the only source of truth for balances), mapped back to
 * core-service's accounts and categories and turned into the user's terms.
 */
@Service
public class BalanceService {

    private static final String ACCOUNT = "account:";
    private static final String CATEGORY = "category:";

    private final SpaceAccess access;
    private final AccountService accounts;
    private final LedgerClient ledger;

    public BalanceService(SpaceAccess access, AccountService accounts, LedgerClient ledger) {
        this.access = access;
        this.accounts = accounts;
        this.ledger = ledger;
    }

    /** Balances are null (not zero) when the ledger can't be reached: unknown is not the same as empty. */
    public List<AccountView> accounts(CurrentUser user, UUID spaceId, boolean includeArchived) {
        List<MoneyAccount> list = accounts.list(user, spaceId, includeArchived);
        Optional<Map<String, Long>> balances = accountBalances(access.read(user, spaceId));
        return list.stream()
                .map(a -> AccountView.of(a, balances.map(b -> a.kind().fromLedger(b.getOrDefault(key(a.ledgerRef(), a.currency()), 0L))).orElse(null)))
                .toList();
    }

    public AccountView account(CurrentUser user, UUID spaceId, UUID accountId) {
        MoneyAccount a = accounts.get(user, spaceId, accountId);
        Long balance = accountBalances(access.read(user, spaceId))
                .map(b -> a.kind().fromLedger(b.getOrDefault(key(a.ledgerRef(), a.currency()), 0L)))
                .orElse(null);
        return AccountView.of(a, balance);
    }

    /**
     * Spending or income per category between two dates (inclusive), positive amounts, optionally per month.
     * A transaction counts on its own date; an edited or deleted one nets out of its original period.
     *
     * @param month first day of the month, or null when not grouped by month
     */
    public record CategoryTotal(UUID categoryId, String currency, LocalDate month, long amount) {
    }

    public List<CategoryTotal> categoryTotals(CurrentUser user, UUID spaceId, String kind, LocalDate from, LocalDate to, boolean byMonth) {
        Space space = access.read(user, spaceId);
        // Expenses are debits (positive), income credits (negative): flip income so both read as positive.
        long sign = kind.equals("INCOME") ? -1 : 1;
        return ledger.totals(space.ledgerBookRef(), kind, from, to, byMonth)
                .orElseThrow(LedgerUnavailableException::new)
                .stream()
                .filter(t -> t.accountRef().startsWith(CATEGORY))
                .map(t -> new CategoryTotal(UUID.fromString(t.accountRef().substring(CATEGORY.length())), t.currency(),
                        t.periodStart(), sign * t.total()))
                .toList();
    }

    private Optional<Map<String, Long>> accountBalances(Space space) {
        return ledger.balances(space.ledgerBookRef()).map(list -> list.stream()
                .filter(b -> b.accountRef().startsWith(ACCOUNT))
                .collect(Collectors.toMap(b -> key(b.accountRef(), b.currency()), LedgerClient.Balance::balance)));
    }

    private static String key(String ref, String currency) {
        return ref + "/" + currency;
    }

    static class LedgerUnavailableException extends ApiException {
        LedgerUnavailableException() {
            super(HttpStatus.SERVICE_UNAVAILABLE, "ledger-unavailable", "totals are not available right now; try again shortly");
        }
    }
}
