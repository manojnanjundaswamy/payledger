package dev.payledger.ledger.common;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Time-ordered UUIDs (RFC 9562 version 7) for rows inserted with plain SQL, where Hibernate's
 * generator isn't involved. Same reasoning as on the entities: inserts append to the B-tree index.
 */
public final class Uuid7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Uuid7() {
    }

    public static UUID next() {
        long millis = System.currentTimeMillis();
        long high = (millis << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFFL);
        long low = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(high, low);
    }
}
