package app.stageminute.platform;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Writes one {@code abuse_signals} row (F-086): a velocity limit hit, a missing device id, later a failed
 * attestation. A signal is never a ban and never blocks a person; the admin console reviews them weekly. The
 * row holds a keyed hash of the device, never the device id (05 F-082), and is kept 12 months.
 *
 * <p>It lives in the shared kernel because more than one module raises signals (account at sign-up, practice at
 * session start) and each must write without reaching into the other's tables.
 */
@Component
public class AbuseSignals {

    private final JdbcClient jdbc;
    private final Clock clock;

    AbuseSignals(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void record(String kind, @Nullable UUID userId, byte @Nullable [] deviceHash) {
        jdbc.sql("INSERT INTO abuse_signals (user_id, device_hash, kind, at) VALUES (:u, :d, :k, :at)")
                .param("u", userId)
                .param("d", deviceHash)
                .param("k", kind)
                .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
    }
}
