package app.stageminute.platform;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Makes a write safe to repeat (server/CLAUDE.md "Idempotent writes"). The app sends the same key when it
 * retries; the first try runs, the others get "in progress" or the stored answer. Entries last
 * {@link SettingKey#IDEMPOTENCY_TTL_MINUTES} minutes and go with the account.
 *
 * <p>One key may be used on more than one endpoint (the app uses the take's key for both the session and the
 * analysis), so callers pass a key that starts with the endpoint, for example {@code "analyze:" + takeKey}.
 */
@Component
public class IdempotencyStore {

    /** What {@link #begin} found. */
    public enum Outcome {
        /** First try: run the request, then call {@link #complete} or {@link #fail}. */
        STARTED,
        /** Another try is running now. */
        IN_PROGRESS,
        /** Already finished; {@link Begin#response()} holds the stored answer. */
        DONE,
        /** The key was used before for a different request. */
        CONFLICT
    }

    public record Begin(Outcome outcome, String response) {}

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Settings settings;

    IdempotencyStore(JdbcClient jdbc, Clock clock, Settings settings) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.settings = settings;
    }

    public Begin begin(UUID userId, String key, byte[] requestHash) {
        OffsetDateTime now = now();
        jdbc.sql("DELETE FROM idempotency_entries WHERE user_id = :u AND key = :k AND created_at < :cutoff")
                .param("u", userId)
                .param("k", key)
                .param("cutoff", cutoff(now))
                .update();

        int inserted = jdbc.sql("INSERT INTO idempotency_entries (user_id, key, request_hash, status, created_at)"
                        + " VALUES (:u, :k, :h, 'in_progress', :now) ON CONFLICT DO NOTHING")
                .param("u", userId)
                .param("k", key)
                .param("h", requestHash)
                .param("now", now)
                .update();
        if (inserted == 1) {
            return new Begin(Outcome.STARTED, null);
        }

        record Existing(boolean sameRequest, String status, String response) {}
        Existing existing = jdbc.sql("SELECT request_hash = :h AS same_request, status, response::text AS response"
                        + " FROM idempotency_entries WHERE user_id = :u AND key = :k")
                .param("h", requestHash)
                .param("u", userId)
                .param("k", key)
                .query((rs, row) ->
                        new Existing(rs.getBoolean("same_request"), rs.getString("status"), rs.getString("response")))
                .optional()
                .orElse(null);
        if (existing == null) {
            return new Begin(Outcome.IN_PROGRESS, null); // removed in between; the caller tries again
        }
        if (!existing.sameRequest()) {
            return new Begin(Outcome.CONFLICT, null);
        }
        return switch (existing.status()) {
            case "done" -> new Begin(Outcome.DONE, existing.response());
            case "failed" -> restart(userId, key, now);
            default -> new Begin(Outcome.IN_PROGRESS, null);
        };
    }

    private Begin restart(UUID userId, String key, OffsetDateTime now) {
        int restarted = jdbc.sql("UPDATE idempotency_entries SET status = 'in_progress', created_at = :now"
                        + " WHERE user_id = :u AND key = :k AND status = 'failed'")
                .param("u", userId)
                .param("k", key)
                .param("now", now)
                .update();
        return new Begin(restarted == 1 ? Outcome.STARTED : Outcome.IN_PROGRESS, null);
    }

    /** Stores the answer. It never holds {@code speech_data} (03-DATABASE.md). */
    public void complete(UUID userId, String key, String responseJson) {
        jdbc.sql("UPDATE idempotency_entries SET status = 'done', response = CAST(:r AS jsonb)"
                        + " WHERE user_id = :u AND key = :k")
                .param("r", responseJson)
                .param("u", userId)
                .param("k", key)
                .update();
    }

    /** The request failed; the same request may be tried again. */
    public void fail(UUID userId, String key) {
        jdbc.sql("UPDATE idempotency_entries SET status = 'failed' WHERE user_id = :u AND key = :k")
                .param("u", userId)
                .param("k", key)
                .update();
    }

    /**
     * Removes the person's stored answers that name {@code id} anywhere in their JSON (as a string value). Used when
     * the thing an answer holds is deleted, so no copy of it outlives the delete (F-046, AD-147). A later repeat of
     * that request runs again instead of replaying the deleted answer.
     *
     * @return how many entries were removed
     */
    public int forgetAnswersNaming(UUID userId, UUID id) {
        return jdbc.sql("DELETE FROM idempotency_entries WHERE user_id = :u AND response IS NOT NULL"
                        + " AND jsonb_path_exists(response, '$.** ? (@ == $id)', jsonb_build_object('id', :id))")
                .param("u", userId)
                .param("id", id.toString())
                .update();
    }

    /** Retention job: removes entries past their time limit. Returns how many. */
    public int purgeExpired() {
        return jdbc.sql("DELETE FROM idempotency_entries WHERE created_at < :cutoff")
                .param("cutoff", cutoff(now()))
                .update();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), clock.getZone());
    }

    private OffsetDateTime cutoff(OffsetDateTime now) {
        return now.minusMinutes(settings.get(SettingKey.IDEMPOTENCY_TTL_MINUTES));
    }
}
