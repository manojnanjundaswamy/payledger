package dev.payledger.core.money;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An account as the app shows it.
 *
 * @param balance in the user's terms (paise): money in it, or for a CARD the amount owed. Null when the ledger
 *                could not be reached; the app then shows the account without a number. Changes made in the last
 *                second may not be included yet (the ledger applies them asynchronously).
 */
public record AccountView(UUID id, UUID spaceId, AccountKind kind, String name, String currency, long openingBalance,
                          LocalDate openingDate, boolean archived, Instant createdAt, Long balance) {

    static AccountView of(MoneyAccount a, Long balance) {
        return new AccountView(a.id(), a.spaceId(), a.kind(), a.name(), a.currency(), a.openingBalance(),
                a.openingDate(), a.archived(), a.createdAt(), balance);
    }
}
