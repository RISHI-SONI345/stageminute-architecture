package app.stageminute.platform;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Ids that leave the server are UUIDv7: ordered by time, not guessable (AD-14, RFC 9562). */
class UuidsTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-26T10:00:00Z"));

    @Test
    void hasVersion7AndTheStandardVariant() {
        UUID id = Uuids.v7(clock);

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void startsWithTheClockMilliseconds() {
        UUID id = Uuids.v7(clock);

        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(clock.instant().toEpochMilli());
    }

    @Test
    void laterIdsSortAfterEarlierOnes() {
        UUID first = Uuids.v7(clock);
        clock.advance(Duration.ofMillis(5));
        UUID second = Uuids.v7(clock);

        assertThat(first.toString()).isLessThan(second.toString());
    }

    @Test
    void twoIdsInTheSameMillisecondDiffer() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(Uuids.v7(clock));
        }

        assertThat(seen).hasSize(1000);
    }
}
