package dev.payledger.notification.feed;

import java.time.Instant;
import java.util.UUID;

/** @param amount signed minor units: positive = money in */
public record Notification(
        long id,
        UUID entryId,
        UUID accountId,
        String ownerId,
        String entryType,
        long amount,
        String currency,
        long balanceAfter,
        String message,
        Instant occurredAt) {
}
