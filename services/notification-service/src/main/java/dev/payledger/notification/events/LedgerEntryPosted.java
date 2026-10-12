package dev.payledger.notification.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * This service's own copy of the ledger's event contract (docs/events/ledger-entry-posted.md).
 * <p>
 * Deliberately not a shared library: services stay independently deployable, and this
 * "tolerant reader" only declares the fields it uses and ignores any the ledger adds later.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LedgerEntryPosted(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        UUID entryId,
        String entryType,
        String currency,
        String description,
        List<Leg> postings) {

    public static final String TYPE = "ledger.entry.posted";
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Leg(UUID accountId, String ownerId, String accountType, long amount, long balanceAfter) {

        public boolean isUserWallet() {
            return "USER".equals(accountType);
        }
    }
}
