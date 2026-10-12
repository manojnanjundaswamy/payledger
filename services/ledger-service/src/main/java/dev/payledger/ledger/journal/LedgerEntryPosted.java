package dev.payledger.ledger.journal;

import dev.payledger.ledger.account.Account;
import dev.payledger.ledger.account.AccountType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Published to Kafka (via the outbox) every time a journal entry is committed.
 * <p>
 * This record IS the public contract with other services (docs/events/ledger-entry-posted.md).
 * It carries the owner and account type of every leg so consumers never have to call back into
 * the ledger to make sense of it. Add fields freely; renaming or removing one needs a new
 * {@code schemaVersion} and a new topic.
 */
public record LedgerEntryPosted(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        UUID entryId,
        EntryType entryType,
        String currency,
        String description,
        List<Leg> postings) {

    public static final String TYPE = "ledger.entry.posted";
    public static final int SCHEMA_VERSION = 1;

    public record Leg(UUID accountId, String ownerId, AccountType accountType, long amount, long balanceAfter) {
    }

    static LedgerEntryPosted of(JournalEntryView entry, String currency, Map<UUID, Account> accounts) {
        List<Leg> legs = entry.postings().stream()
                .map(p -> {
                    Account account = accounts.get(p.accountId());
                    return new Leg(p.accountId(), account.getOwnerId(), account.getType(), p.amount(), p.balanceAfter());
                })
                .toList();
        return new LedgerEntryPosted(UUID.randomUUID(), TYPE, SCHEMA_VERSION, entry.createdAt(), entry.id(),
                entry.type(), currency, entry.description(), legs);
    }
}
