package dev.payledger.ledger.journal;

/**
 * Cancels an earlier entry by posting its exact negation, dated like the original so the
 * original period's totals net to zero. This is how edits and deletes work in an append-only ledger:
 * reverse the old version, then post the new one.
 *
 * @param idempotencyKey         key of the reversal itself
 * @param reversesIdempotencyKey key the original entry was posted with
 */
public record ReversalCommand(String idempotencyKey, String reversesIdempotencyKey, String description) {
}
