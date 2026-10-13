package dev.payledger.core.money;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A bank account, cash, card or wallet.
 *
 * @param openingBalance in the user's terms (paise): money in the account, or for a CARD the amount owed
 */
public record MoneyAccount(UUID id, UUID spaceId, AccountKind kind, String name, String currency,
                           long openingBalance, LocalDate openingDate, boolean archived, Instant createdAt) {

    public String ledgerRef() {
        return ledgerRef(id);
    }

    static String ledgerRef(UUID id) {
        return "account:" + id;
    }
}
