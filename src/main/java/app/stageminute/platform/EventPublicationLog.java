package app.stageminute.platform;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads Spring Modulith's event table ({@code event_publication}, AD-07) so a job that did not finish is findable
 * (03 F-094: alert on any job older than {@code job_alert_hours}). It reads names, times and a try count only, never
 * the stored event, which holds ids. The admin page that lists these is ticket #27.
 */
@Component
public class EventPublicationLog {

    /** The event type name that ends the deletion events ({@code Deletion.Requested}). */
    public static final String DELETION_EVENT_SUFFIX = "Deletion$Requested";

    /**
     * One listener that has not finished one event.
     *
     * @param listenerId which listener (class and method), for example the Clerk deletion listener
     * @param eventType the event's class name
     * @param publishedAt when the event was published
     * @param attempts how many times the listener has been tried
     */
    public record Incomplete(String listenerId, String eventType, OffsetDateTime publishedAt, int attempts) {

        public boolean isDeletion() {
            return eventType.endsWith(DELETION_EVENT_SUFFIX);
        }
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Settings settings;

    EventPublicationLog(JdbcClient jdbc, Clock clock, Settings settings) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.settings = settings;
    }

    /** Every listener that still has not finished an event published more than {@code olderThan} ago, oldest first. */
    public List<Incomplete> incompleteOlderThan(Duration olderThan) {
        OffsetDateTime cutoff =
                OffsetDateTime.ofInstant(clock.instant(), clock.getZone()).minus(olderThan);
        return jdbc.sql("SELECT listener_id, event_type, publication_date, COALESCE(completion_attempts, 0) AS attempts"
                        + " FROM event_publication WHERE completion_date IS NULL AND publication_date < :cutoff"
                        + " ORDER BY publication_date")
                .param("cutoff", cutoff)
                .query((rs, row) -> new Incomplete(
                        rs.getString("listener_id"),
                        rs.getString("event_type"),
                        rs.getObject("publication_date", OffsetDateTime.class),
                        rs.getInt("attempts")))
                .list();
    }

    /** The deletion events that are overdue: older than {@code job_alert_hours} and not finished (05 F-082). */
    public List<Incomplete> overdueDeletions() {
        return incompleteOlderThan(alertAge()).stream()
                .filter(Incomplete::isDeletion)
                .toList();
    }

    /** Any event that is overdue: the age after which a job that did not finish is an alert. */
    public List<Incomplete> overdue() {
        return incompleteOlderThan(alertAge());
    }

    private Duration alertAge() {
        return Duration.ofHours(settings.get(SettingKey.JOB_ALERT_HOURS));
    }
}
