package dev.payledger.core.ledger;

import dev.payledger.core.common.Uuid7;
import dev.payledger.core.outbox.OutboxWriter;
import dev.payledger.core.space.SpaceAccess.Space;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Queues ledger writes in the outbox, in the caller's transaction (docs/events/ledger-commands.md).
 * <p>
 * core-service never calls the ledger synchronously to write: the user's change and the command
 * commit together or not at all, and ledger-service applies the command later (idempotently, by
 * {@code idempotencyKey}). Commands are keyed by their book's owner (a space or a group), so a reversal never
 * overtakes the entry it reverses.
 */
@Component
public class LedgerCommands {

    public static final String POST = "ledger.post";
    public static final String REVERSE = "ledger.reverse";
    static final int SCHEMA_VERSION = 1;

    private final OutboxWriter outbox;

    public LedgerCommands(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public void post(Space space, String idempotencyKey, String entryType, LocalDate effectiveDate,
                     String description, String sourceRef, List<Leg> legs) {
        post(new Book("Space", space.id(), space.ledgerBookRef(), space.kind()), idempotencyKey, entryType, effectiveDate,
                description, sourceRef, legs);
    }

    public void reverse(Space space, String idempotencyKey, String reversesIdempotencyKey, String description) {
        reverse(new Book("Space", space.id(), space.ledgerBookRef(), space.kind()), idempotencyKey, reversesIdempotencyKey, description);
    }

    /**
     * A ledger book and what its commands are keyed by (a space, or a group: docs/adr/0007), so one book's
     * commands stay in order on one partition.
     */
    public record Book(String aggregateType, UUID aggregateId, String ref, String kind) {

        public static Book group(UUID groupId) {
            return new Book("Group", groupId, "group:" + groupId, "GROUP");
        }
    }

    public void post(Book book, String idempotencyKey, String entryType, LocalDate effectiveDate,
                     String description, String sourceRef, List<Leg> legs) {
        long sum = legs.stream().mapToLong(Leg::amount).sum();
        if (legs.size() < 2 || sum != 0) {
            // A programming error, not user input: core-service builds every leg itself.
            throw new IllegalArgumentException("ledger entry must have 2+ legs summing to zero, got " + legs);
        }
        UUID commandId = Uuid7.next();
        outbox.append(book.aggregateType(), book.aggregateId(), commandId, POST, new Post(commandId, POST, SCHEMA_VERSION, Instant.now(),
                idempotencyKey, book.ref(), book.kind(), entryType, effectiveDate, description, sourceRef, legs));
    }

    public void reverse(Book book, String idempotencyKey, String reversesIdempotencyKey, String description) {
        UUID commandId = Uuid7.next();
        outbox.append(book.aggregateType(), book.aggregateId(), commandId, REVERSE, new Reverse(commandId, REVERSE, SCHEMA_VERSION, Instant.now(),
                idempotencyKey, book.ref(), reversesIdempotencyKey, description));
    }

    /**
     * @param accountType ASSET, LIABILITY, EQUITY, INCOME or EXPENSE
     * @param amount      signed minor units: positive = debit
     */
    public record Leg(String accountRef, String accountType, String currency, long amount) {
    }

    record Post(UUID commandId, String commandType, int schemaVersion, Instant createdAt, String idempotencyKey,
                String bookRef, String bookKind, String entryType, LocalDate effectiveDate, String description,
                String sourceRef, List<Leg> legs) {
    }

    record Reverse(UUID commandId, String commandType, int schemaVersion, Instant createdAt, String idempotencyKey,
                   String bookRef, String reversesIdempotencyKey, String description) {
    }
}
