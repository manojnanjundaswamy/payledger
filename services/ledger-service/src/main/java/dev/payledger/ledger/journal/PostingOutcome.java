package dev.payledger.ledger.journal;

/**
 * @param replayed true when the idempotency key had already been processed and the
 *                 original result is returned instead of moving money again
 */
public record PostingOutcome(JournalEntryView entry, boolean replayed) {
}
