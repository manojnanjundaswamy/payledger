package dev.payledger.ledger.journal;

import dev.payledger.ledger.account.AccountType;
import dev.payledger.ledger.book.BookKind;

import java.time.LocalDate;
import java.util.List;

/**
 * A request to record one balanced journal entry in one book.
 * <p>
 * Books and accounts are addressed by the caller's own references and are created on first use,
 * so the caller never has to create them up front or store ledger ids.
 *
 * @param idempotencyKey the same key always yields the same entry (retries are safe)
 * @param bookRef        e.g. {@code space:<uuid>}; created with {@code bookKind} if it doesn't exist
 * @param entryType      the caller's label, e.g. EXPENSE, SPLIT, SETTLEMENT (upper case and underscores)
 * @param effectiveDate  the business date the money moved; may be in the past
 * @param sourceRef      optional: what caused the entry on the caller's side, e.g. {@code txn:<uuid>}
 * @param legs           signed minor-unit amounts (positive = debit); must sum to zero per currency
 */
public record PostingCommand(
        String idempotencyKey,
        String bookRef,
        BookKind bookKind,
        String entryType,
        LocalDate effectiveDate,
        String description,
        String sourceRef,
        List<Leg> legs) {

    /** @param accountRef created with {@code accountType} on first use; must keep that type afterwards */
    public record Leg(String accountRef, AccountType accountType, String currency, long amount) {
    }
}
