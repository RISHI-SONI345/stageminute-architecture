package app.stageminute.account;

import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.Reason;
import app.stageminute.platform.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The one day calculator (03 F-050): a person's day is the stored IANA zone plus the server clock. The phone
 * clock and the phone's zone grant nothing, so every module asks here for "today" and "when does it reset".
 */
@Component
public class AccountCalendar {

    private final JdbcClient jdbc;
    private final Clock clock;

    AccountCalendar(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public LocalDate today(UserId id) {
        return LocalDate.ofInstant(clock.instant(), zone(id));
    }

    /** The account day an instant falls on, in the person's stored zone (a report's day, a session's day). */
    public LocalDate dayOf(UserId id, Instant instant) {
        return LocalDate.ofInstant(instant, zone(id));
    }

    /** The start of the person's next day: when daily counters start again. */
    public Instant resetsAt(UserId id) {
        ZoneId zone = zone(id);
        return today(id).plusDays(1).atStartOfDay(zone).toInstant();
    }

    /** The moment this account day starts in the person's stored zone (for a reset time of a later day). */
    public Instant startOf(UserId id, LocalDate day) {
        return day.atStartOfDay(zone(id)).toInstant();
    }

    private ZoneId zone(UserId id) {
        String name = jdbc.sql("SELECT timezone FROM users WHERE id = :id")
                .param("id", id.value())
                .query(String.class)
                .optional()
                .orElseThrow(() -> new BusinessRefusal(Reason.ACCOUNT_DELETED));
        return ZoneId.of(name);
    }
}
