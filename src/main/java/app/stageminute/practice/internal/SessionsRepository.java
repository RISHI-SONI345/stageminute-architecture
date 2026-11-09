package app.stageminute.practice.internal;

import app.stageminute.platform.UserId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The SQL of {@code practice_sessions}. {@code UNIQUE (user_id, take_key)} makes a session per take safe to
 * repeat even after an idempotency entry has expired (AD-106). It holds no rule.
 */
@Repository
public class SessionsRepository {

    /** A stored session, as far as a repeated reserve needs it. */
    public record StoredSession(UUID id, LocalDate day, String source) {}

    private final JdbcClient jdbc;

    SessionsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredSession> findByTakeKey(UserId user, String takeKey) {
        return jdbc.sql("SELECT id, local_date, source FROM practice_sessions WHERE user_id = :u AND take_key = :k")
                .param("u", user.value())
                .param("k", takeKey)
                .query((rs, n) -> new StoredSession(
                        rs.getObject("id", UUID.class),
                        rs.getObject("local_date", LocalDate.class),
                        rs.getString("source")))
                .optional();
    }

    /**
     * True when this person has a session that is reserved or committed (a released one does not count),
     * leaving out {@code except} when it is given.
     */
    public boolean hasLiveSessionOtherThan(UserId user, @Nullable UUID except) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM practice_sessions WHERE user_id = :u"
                        + " AND state IN ('reserved','committed') AND id IS DISTINCT FROM CAST(:except AS uuid))")
                .param("u", user.value())
                .param("except", except)
                .query(Boolean.class)
                .single();
    }

    /** True when this person has a committed session other than {@code except}. */
    public boolean hasCommittedSessionOtherThan(UserId user, UUID except) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM practice_sessions WHERE user_id = :u"
                        + " AND state = 'committed' AND id <> :except)")
                .param("u", user.value())
                .param("except", except)
                .query(Boolean.class)
                .single();
    }

    /** How many sessions this person has finished with a scored result (committed ones only). */
    public int completedCount(UserId user) {
        return jdbc.sql("SELECT count(*) FROM practice_sessions WHERE user_id = :u AND state = 'committed'")
                .param("u", user.value())
                .query(Integer.class)
                .single();
    }

    /** What the totals show (03 F-055): sessions with a delivered result, and how long the person spoke in them. */
    public record Totals(int sessions, long speakingMs) {}

    /**
     * The totals. They read {@code practice_sessions} only, so deleting a report never shrinks them. A session that
     * was given back after our failure is not a delivered result and is not counted here.
     */
    public Totals totals(UserId user) {
        return jdbc.sql("SELECT count(*) AS sessions, coalesce(sum(speaking_ms), 0) AS ms FROM practice_sessions"
                        + " WHERE user_id = :u AND state = 'committed'")
                .param("u", user.value())
                .query((rs, n) -> new Totals(rs.getInt("sessions"), rs.getLong("ms")))
                .single();
    }

    /**
     * The distinct start dates of the sessions that count for the streak (03 F-053): a delivered result, or a session
     * given back with one of {@code giveBackReasons} (our own failure). A no-score take, a discard or a timeout never
     * counts. The date is the day the session started in the person's zone, wherever the result came.
     */
    public List<LocalDate> countedDays(UserId user, List<String> giveBackReasons) {
        return jdbc.sql("SELECT DISTINCT local_date FROM practice_sessions WHERE user_id = :u"
                        + " AND (state = 'committed' OR (state = 'released' AND release_reason IN (:reasons)))"
                        + " ORDER BY local_date")
                .param("u", user.value())
                .param("reasons", giveBackReasons)
                .query(LocalDate.class)
                .list();
    }

    /**
     * The topic ids of this person's last {@code limit} sessions of any state, oldest first. A released session
     * counts: the person was shown that topic.
     */
    public List<String> recentTopicIds(UserId user, int limit) {
        return jdbc.sql("SELECT topic_id FROM (SELECT topic_id, reserved_at, id FROM practice_sessions"
                        + " WHERE user_id = :u ORDER BY reserved_at DESC, id DESC LIMIT :limit) recent"
                        + " ORDER BY reserved_at, id")
                .param("u", user.value())
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    /** Moves a reserved session to committed; empty when it is not reserved (or not this person's). */
    public Optional<LocalDate> commitReserved(UserId user, UUID id, Instant at) {
        return jdbc.sql("UPDATE practice_sessions SET state = 'committed', committed_at = :at"
                        + " WHERE id = :id AND user_id = :u AND state = 'reserved' RETURNING local_date")
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .param("id", id)
                .param("u", user.value())
                .query(LocalDate.class)
                .optional();
    }

    /** Moves a released session to committed; empty when it is not released (or not this person's). */
    public Optional<LocalDate> commitReleased(UserId user, UUID id, Instant at) {
        return jdbc.sql("UPDATE practice_sessions SET state = 'committed', committed_at = :at, released_at = NULL,"
                        + " release_reason = NULL"
                        + " WHERE id = :id AND user_id = :u AND state = 'released' RETURNING local_date")
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .param("id", id)
                .param("u", user.value())
                .query(LocalDate.class)
                .optional();
    }

    /**
     * Moves a reserved session to released; empty when it is not reserved (or not this person's). A session being
     * analysed is released only when {@code evenWhileAnalysing} (the server's own give-back after a failure); a
     * release the person sends during an analysis changes nothing (C-52).
     */
    public Optional<LocalDate> releaseReserved(
            UserId user, UUID id, String reason, Instant at, boolean evenWhileAnalysing) {
        return jdbc.sql("UPDATE practice_sessions SET state = 'released', released_at = :at, release_reason = :r,"
                        + " analysing_since = NULL"
                        + " WHERE id = :id AND user_id = :u AND state = 'reserved'"
                        + (evenWhileAnalysing ? "" : " AND analysing_since IS NULL")
                        + " RETURNING local_date")
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .param("r", reason)
                .param("id", id)
                .param("u", user.value())
                .query(LocalDate.class)
                .optional();
    }

    /** What an analysis needs to know about a session. */
    public record SessionForAnalysis(UUID id, String topicId, String style, String state, int scoredAttempts) {}

    public Optional<SessionForAnalysis> findForAnalysis(UserId user, UUID id) {
        return jdbc.sql("SELECT id, topic_id, style::text AS style, state, attempt_count FROM practice_sessions"
                        + " WHERE id = :id AND user_id = :u")
                .param("id", id)
                .param("u", user.value())
                .query((rs, row) -> new SessionForAnalysis(
                        rs.getObject("id", UUID.class),
                        rs.getString("topic_id"),
                        rs.getString("style"),
                        rs.getString("state"),
                        rs.getInt("attempt_count")))
                .optional();
    }

    /**
     * Marks the session as being analysed, in one conditional update (C-52): only a reserved or committed session,
     * and only when no other analysis of it started after {@code staleBefore}. False when nothing was marked.
     */
    public boolean markAnalysing(UserId user, UUID id, Instant at, Instant staleBefore) {
        return jdbc.sql("UPDATE practice_sessions SET analysing_since = :at"
                                + " WHERE id = :id AND user_id = :u AND state IN ('reserved','committed')"
                                + " AND (analysing_since IS NULL OR analysing_since < :stale)")
                        .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                        .param("stale", OffsetDateTime.ofInstant(staleBefore, ZoneOffset.UTC))
                        .param("id", id)
                        .param("u", user.value())
                        .update()
                == 1;
    }

    public void clearAnalysing(UserId user, UUID id) {
        jdbc.sql("UPDATE practice_sessions SET analysing_since = NULL WHERE id = :id AND user_id = :u")
                .param("id", id)
                .param("u", user.value())
                .update();
    }

    /** One more scored attempt: the count, the speaking time for the totals (F-055), and the analysis is over. */
    public void recordScoredAttempt(UserId user, UUID id, int speakingMs) {
        jdbc.sql("UPDATE practice_sessions SET attempt_count = attempt_count + 1,"
                        + " speaking_ms = speaking_ms + :ms, analysing_since = NULL WHERE id = :id AND user_id = :u")
                .param("ms", speakingMs)
                .param("id", id)
                .param("u", user.value())
                .update();
    }

    /**
     * Releases every session still reserved at {@code cutoff} or earlier, in one statement: the session rows,
     * one {@code expire} ledger row of -1 each, and the count taken off the day each one counted on. A session
     * another call already changed is skipped, so two runs at once release it once. Answers how many.
     */
    public int releaseReservedBefore(Instant cutoff, String reason, Instant at) {
        return jdbc.sql("""
                        WITH released AS (
                          UPDATE practice_sessions SET state = 'released', released_at = :at, release_reason = :reason
                           WHERE state = 'reserved' AND reserved_at <= :cutoff
                          RETURNING id, user_id, local_date
                        ), ledger AS (
                          INSERT INTO quota_events (user_id, local_date, kind, delta, session_id, reason, at)
                          SELECT user_id, local_date, 'expire', -1, id, :reason, :at FROM released
                        ), per_day AS (
                          SELECT user_id, local_date, count(*) AS n FROM released GROUP BY user_id, local_date
                        ), counted AS (
                          UPDATE daily_counters c SET sessions_used = c.sessions_used - per_day.n
                            FROM per_day
                           WHERE c.user_id = per_day.user_id AND c.local_date = per_day.local_date
                        )
                        SELECT count(*) FROM released
                        """)
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .param("reason", reason)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query(Integer.class)
                .single();
    }

    /** Adds a reserved session; false when this person already has a session for {@code takeKey}. */
    public boolean insertReserved(
            UUID id,
            UserId user,
            String takeKey,
            String topicId,
            String style,
            String level,
            LocalDate day,
            String source,
            Instant at) {
        return jdbc.sql("INSERT INTO practice_sessions"
                                + " (id, user_id, take_key, topic_id, style, level, local_date, source, state, reserved_at)"
                                + " VALUES (:id, :u, :k, :topic, :style, :level, :d, :source, 'reserved', :at)"
                                + " ON CONFLICT (user_id, take_key) DO NOTHING")
                        .param("id", id)
                        .param("u", user.value())
                        .param("k", takeKey)
                        .param("topic", topicId)
                        .param("style", style)
                        .param("level", level)
                        .param("d", day)
                        .param("source", source)
                        .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                        .update()
                == 1;
    }
}
