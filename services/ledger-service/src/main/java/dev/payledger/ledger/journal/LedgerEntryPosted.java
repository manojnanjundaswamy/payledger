package dev.payledger.ledger.journal;

import dev.payledger.ledger.account.AccountType;
import dev.payledger.ledger.book.BookKind;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Published to Kafka (via the outbox) every time a journal entry is committed, reversals included.
 * <p>
 * This record IS the public contract with other services (docs/events/ledger-entry-posted.md).
 * Add fields freely; renaming or removing one needs a new {@code schemaVersion} and a new topic.
 */
public record LedgerEntryPosted(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        UUID entryId,
        String idempotencyKey,
        String bookRef,
        BookKind bookKind,
        String entryType,
        LocalDate effectiveDate,
        String description,
        String sourceRef,
        UUID reversesEntryId,
        List<Leg> postings) {

    public static final String TYPE = "ledger.entry.posted";
    public static final int SCHEMA_VERSION = 2;

    public record Leg(String accountRef, AccountType accountType, String currency, long amount, long balanceAfter) {
    }

    static LedgerEntryPosted of(JournalEntryView entry, BookKind bookKind) {
        List<Leg> legs = entry.postings().stream()
                .map(p -> new Leg(p.accountRef(), p.accountType(), p.currency(), p.amount(), p.balanceAfter()))
                .toList();
        return new LedgerEntryPosted(UUID.randomUUID(), TYPE, SCHEMA_VERSION, entry.createdAt(), entry.id(),
                entry.idempotencyKey(), entry.bookRef(), bookKind, entry.type(), entry.effectiveDate(),
                entry.description(), entry.sourceRef(), entry.reversesEntryId(), legs);
    }
}
