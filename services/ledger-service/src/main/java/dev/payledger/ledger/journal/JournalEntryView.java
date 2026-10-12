package dev.payledger.ledger.journal;

import dev.payledger.ledger.account.Account;
import dev.payledger.ledger.account.AccountType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read model returned to callers; built inside the transaction so no lazy loading leaks out. */
public record JournalEntryView(
        UUID id,
        String idempotencyKey,
        String bookRef,
        String type,
        LocalDate effectiveDate,
        String description,
        String sourceRef,
        UUID reversesEntryId,
        Instant createdAt,
        List<PostingView> postings) {

    public record PostingView(long id, String accountRef, AccountType accountType, String currency, long amount,
                              long balanceAfter) {
    }

    static JournalEntryView of(JournalEntry entry, String bookRef, Map<UUID, Account> accounts) {
        List<PostingView> postings = entry.getPostings().stream()
                .map(p -> {
                    Account account = accounts.get(p.getAccountId());
                    return new PostingView(p.getId(), account.getRef(), account.getType(), p.getCurrency(),
                            p.getAmount(), p.getBalanceAfter());
                })
                .toList();
        return new JournalEntryView(entry.getId(), entry.getIdempotencyKey(), bookRef, entry.getType(),
                entry.getEffectiveDate(), entry.getDescription(), entry.getSourceRef(), entry.getReversesEntryId(),
                entry.getCreatedAt(), postings);
    }
}
