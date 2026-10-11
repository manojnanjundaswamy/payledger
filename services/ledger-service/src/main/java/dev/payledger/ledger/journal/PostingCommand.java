package dev.payledger.ledger.journal;

import java.util.List;
import java.util.UUID;

/**
 * A request to record one balanced journal entry.
 *
 * @param idempotencyKey client-supplied key; the same key always yields the same entry
 * @param requestHash    fingerprint of the business payload, used to detect key reuse with different data
 * @param legs           signed amounts per account; must sum to zero
 */
public record PostingCommand(
        String idempotencyKey,
        String requestHash,
        EntryType type,
        String currency,
        String description,
        List<Leg> legs) {

    public record Leg(UUID accountId, long amount) {
    }
}
