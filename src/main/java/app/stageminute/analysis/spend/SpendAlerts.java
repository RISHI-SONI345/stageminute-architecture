package app.stageminute.analysis.spend;

import app.stageminute.platform.RequestContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The alerts of F-087 as a metric and a log line, each once per UTC day: 70 % of the budget reached, 100 % (free users
 * paused), 150 % (the Pro ceiling lowered). The counter is {@code ai.spend.level{level}} and the line carries the level
 * and the whole percent, never an amount per person. Delivery to the founder's phone is #7 and #26. The "already sent"
 * memory is in this process: after a restart a level that was reached earlier in the day can fire once more, which is
 * safe for an alert (the throttle itself is decided from the database, never from this memory).
 */
final class SpendAlerts {

    private static final Logger LOG = LoggerFactory.getLogger(SpendAlerts.class);

    private final MeterRegistry meters;
    private LocalDate day;
    private final Set<ThrottlePolicy.AlertLevel> fired = EnumSet.noneOf(ThrottlePolicy.AlertLevel.class);

    SpendAlerts(MeterRegistry meters) {
        this.meters = meters;
    }

    /** Fires every level that {@code confirmedMicros} has newly reached today. Safe to call on every confirmed call. */
    synchronized void check(long confirmedMicros, ThrottlePolicy.Levels levels, LocalDate utcDay) {
        if (!utcDay.equals(day)) {
            day = utcDay;
            fired.clear();
        }
        for (ThrottlePolicy.AlertLevel level : ThrottlePolicy.alertsReached(confirmedMicros, levels)) {
            if (fired.add(level)) {
                String tag = level.name().toLowerCase(Locale.ROOT);
                meters.counter("ai.spend.level", "level", tag).increment();
                int percent = ThrottlePolicy.percentOf(confirmedMicros, levels);
                RequestContext.withoutPerson(
                        () -> LOG.warn("ai.spend.level_reached level={} percent={}", tag, percent));
            }
        }
    }
}
