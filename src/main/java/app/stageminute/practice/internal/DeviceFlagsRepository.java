package app.stageminute.practice.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The SQL of the first-session half of {@code device_flags} (D-31: the ad-free first session follows the
 * device as well as the account). The row is keyed by a keyed hash of the device id, never the id. It holds
 * no rule.
 */
@Repository
public class DeviceFlagsRepository {

    private final JdbcClient jdbc;

    DeviceFlagsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** True when a session on this device has already been the ad-free first one; false for an unknown device. */
    public boolean firstSessionUsed(byte[] deviceHash) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM device_flags WHERE device_hash = :h AND first_session_used)")
                .param("h", deviceHash)
                .query(Boolean.class)
                .single();
    }

    /** Marks the device's ad-free first session as used; makes the row if the device is new. */
    public void markFirstSessionUsed(byte[] deviceHash, LocalDate today, Instant now) {
        jdbc.sql("INSERT INTO device_flags (device_hash, platform, first_session_used, window_start, updated_at)"
                        + " VALUES (:h, 'unknown', true, :today, :now)"
                        + " ON CONFLICT (device_hash) DO UPDATE SET first_session_used = true, updated_at = :now")
                .param("h", deviceHash)
                .param("today", today)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }
}
