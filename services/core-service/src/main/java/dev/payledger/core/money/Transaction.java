package dev.payledger.core.money;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One expense, income or transfer as the user entered it.
 *
 * @param amount  always positive (paise); {@code kind} gives the direction
 * @param version send it back when editing or deleting (optimistic locking)
 */
public record Transaction(UUID id, String kind, long amount, String currency, UUID accountId, UUID toAccountId,
                          UUID categoryId, LocalDate occurredOn, String description, String note, List<String> tags,
                          int version, Instant createdAt, Instant updatedAt) {

    public String ledgerRef() {
        return ledgerRef(id);
    }

    static String ledgerRef(UUID id) {
        return "txn:" + id;
    }
}
