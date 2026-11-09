package app.stageminute.platform;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * One server runs a scheduled job at a time (table {@code job_locks}). A lock expires by itself, so a crashed
 * server never blocks the job for good.
 */
@Component
public class JobLocks {

    private final JdbcClient jdbc;
    private final Clock clock;

    JobLocks(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** True when {@code owner} now holds the lock for at most {@code atMostFor}. One atomic statement. */
    public boolean tryLock(String name, Duration atMostFor, String owner) {
        OffsetDateTime now = now();
        int rows = jdbc.sql("INSERT INTO job_locks (name, lock_until, locked_at, locked_by)"
                        + " VALUES (:name, :until, :now, :owner)"
                        + " ON CONFLICT (name) DO UPDATE SET lock_until = EXCLUDED.lock_until,"
                        + " locked_at = EXCLUDED.locked_at, locked_by = EXCLUDED.locked_by"
                        + " WHERE job_locks.lock_until <= :now")
                .param("name", name)
                .param("until", now.plus(atMostFor))
                .param("now", now)
                .param("owner", owner)
                .update();
        return rows == 1;
    }

    /** Frees the lock early. Only the owner can. */
    public void release(String name, String owner) {
        jdbc.sql("UPDATE job_locks SET lock_until = :now WHERE name = :name AND locked_by = :owner")
                .param("now", now())
                .param("name", name)
                .param("owner", owner)
                .update();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), clock.getZone());
    }
}
