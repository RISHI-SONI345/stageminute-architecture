package app.stageminute.platform;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * Makes UUIDv7 ids (RFC 9562, AD-14): the first 48 bits are the server clock in milliseconds, so rows sort
 * by creation time and index inserts stay in order; the rest is random, so an id cannot be guessed.
 */
public final class Uuids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Uuids() {}

    public static UUID v7(Clock clock) {
        long millis = clock.millis() & 0xFFFF_FFFF_FFFFL;
        long randomHigh = RANDOM.nextLong();
        long randomLow = RANDOM.nextLong();
        // 48 bits time | 4 bits version (7) | 12 random bits
        long most = (millis << 16) | 0x7000L | (randomHigh & 0x0FFFL);
        // 2 bits variant (10) | 62 random bits
        long least = (randomLow & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(most, least);
    }
}
