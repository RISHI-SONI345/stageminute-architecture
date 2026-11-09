package app.stageminute.progress.internal;

import app.stageminute.platform.UserId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The SQL of {@code streak_freezes}, {@code outage_windows}, {@code failed_start_log} and the milestone rows of
 * {@code seen_markers}. No rule lives here. Every write is safe to repeat.
 */
@Repository
public class ProgressRepository {

    private final JdbcClient jdbc;

    ProgressRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Set<LocalDate> frozenDays(UserId user) {
        return jdbc.sql("SELECT local_date FROM streak_freezes WHERE user_id = :u")
                .param("u", user.value())
                .query(LocalDate.class)
                .set();
    }

    /** Freezes a day for the person; a day that is already frozen keeps its first reason. */
    public void freeze(UserId user, LocalDate day, String reason) {
        jdbc.sql("INSERT INTO streak_freezes (user_id, local_date, reason) VALUES (:u, :d, :r)"
                        + " ON CONFLICT (user_id, local_date) DO NOTHING")
                .param("u", user.value())
                .param("d", day)
                .param("r", reason)
                .update();
    }

    public void logFailedStart(UserId user, LocalDate day, String kind, Instant at) {
        jdbc.sql("INSERT INTO failed_start_log (user_id, local_date, kind, at) VALUES (:u, :d, :k, :at)")
                .param("u", user.value())
                .param("d", day)
                .param("k", kind)
                .param("at", utc(at))
                .update();
    }

    public boolean insideOutageWindow(Instant at) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM outage_windows WHERE :at BETWEEN starts_at AND ends_at)")
                .param("at", utc(at))
                .query(Boolean.class)
                .single();
    }

    /** Adds the window unless the very same one is there. @return true when this call added it */
    public boolean insertWindow(Instant from, Instant to, String createdBy) {
        return jdbc.sql("INSERT INTO outage_windows (starts_at, ends_at, created_by)"
                                + " SELECT :from, :to, :by WHERE NOT EXISTS"
                                + " (SELECT 1 FROM outage_windows WHERE starts_at = :from AND ends_at = :to)")
                        .param("from", utc(from))
                        .param("to", utc(to))
                        .param("by", createdBy)
                        .update()
                == 1;
    }

    /** Freezes the day of every failed start logged inside the window. @return how many days were newly frozen */
    public int freezeFailedStartsBetween(Instant from, Instant to) {
        return jdbc.sql("INSERT INTO streak_freezes (user_id, local_date, reason)"
                        + " SELECT DISTINCT user_id, local_date, 'outage' FROM failed_start_log"
                        + " WHERE at BETWEEN :from AND :to ON CONFLICT (user_id, local_date) DO NOTHING")
                .param("from", utc(from))
                .param("to", utc(to))
                .update();
    }

    /** Removes failed starts logged before {@code cutoff}. @return how many */
    public int purgeFailedStartsBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM failed_start_log WHERE at < :cutoff")
                .param("cutoff", utc(cutoff))
                .update();
    }

    /** The milestones (in days) the person has seen. */
    public Set<Integer> seenMilestones(UserId user) {
        List<String> markers = jdbc.sql(
                        "SELECT marker FROM seen_markers WHERE user_id = :u AND marker LIKE 'milestone\\_%'")
                .param("u", user.value())
                .query(String.class)
                .list();
        return markers.stream()
                .map(Milestones::daysOf)
                .filter(days -> days != null)
                .collect(Collectors.toUnmodifiableSet());
    }

    public void markSeen(UserId user, List<String> markers, Instant at) {
        for (String marker : markers) {
            jdbc.sql("INSERT INTO seen_markers (user_id, marker, seen_at) VALUES (:u, :m, :at)"
                            + " ON CONFLICT (user_id, marker) DO NOTHING")
                    .param("u", user.value())
                    .param("m", marker)
                    .param("at", utc(at))
                    .update();
        }
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
