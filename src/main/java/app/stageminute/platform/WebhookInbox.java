package app.stageminute.platform;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The webhook inbox (AD-21, 03 F-092): the one place that answers "have we seen this message before?" for
 * every sender (Clerk now; RevenueCat, Apple and AdMob later). It keeps the sender, the message id and what
 * we did with it, 90 days, and never the payload.
 *
 * <p>Use it inside one database transaction together with the work: {@link #claim} first, then the work, then
 * {@link #finish}. If the work fails the transaction rolls back, the claim disappears with it, and the
 * sender's retry is handled as a first delivery. Two deliveries at the same moment cannot both claim: the
 * second waits for the first to commit and then sees the message as already handled.
 */
@Component
public class WebhookInbox {

    /** What we did with a message (the values the table allows). */
    public enum Outcome {
        APPLIED("applied"),
        IGNORED_OLDER("ignored_older"),
        IGNORED_UNKNOWN_USER("ignored_unknown_user"),
        FAILED("failed");

        private final String value;

        Outcome(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }
    }

    private static final int KEEP_DAYS = 90;

    private final JdbcClient jdbc;
    private final Clock clock;

    WebhookInbox(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** True the first time this message is seen, false for a repeat. Call it in the same transaction as the work. */
    public boolean claim(String source, String eventId) {
        return jdbc.sql("INSERT INTO webhook_events (source, event_id, received_at, outcome, error)"
                                + " VALUES (:s, :e, :now, 'failed', 'in progress') ON CONFLICT DO NOTHING")
                        .param("s", source)
                        .param("e", eventId)
                        .param("now", now())
                        .update()
                == 1;
    }

    public void finish(String source, String eventId, Outcome outcome) {
        jdbc.sql("UPDATE webhook_events SET outcome = :o, error = NULL WHERE source = :s AND event_id = :e")
                .param("o", outcome.value())
                .param("s", source)
                .param("e", eventId)
                .update();
    }

    /** Retention job (ticket #28 schedules it): messages older than 90 days. */
    public int purgeExpired() {
        return jdbc.sql("DELETE FROM webhook_events WHERE received_at < :cutoff")
                .param("cutoff", now().minusDays(KEEP_DAYS))
                .update();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
