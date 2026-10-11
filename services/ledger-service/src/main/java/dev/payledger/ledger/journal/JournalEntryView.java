package dev.payledger.ledger.journal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read model returned to callers; built inside the transaction so no lazy loading leaks out. */
public record JournalEntryView(
        UUID id,
        EntryType type,
        String description,
        Instant createdAt,
        List<PostingView> postings) {

    public record PostingView(long id, UUID accountId, long amount, String currency, long balanceAfter) {

        static PostingView of(Posting posting) {
            return new PostingView(posting.getId(), posting.getAccountId(), posting.getAmount(),
                    posting.getCurrency(), posting.getBalanceAfter());
        }
    }

    static JournalEntryView of(JournalEntry entry) {
        return new JournalEntryView(entry.getId(), entry.getType(), entry.getDescription(), entry.getCreatedAt(),
                entry.getPostings().stream().map(PostingView::of).toList());
    }
}
