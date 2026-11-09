package app.stageminute.progress.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.account.AccountService;
import app.stageminute.account.NewAccountContext;
import app.stageminute.platform.UserId;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The failed-start log is kept for {@code failed_start_retention_days} (90) and then removed (03 section 3). */
class FailedStartPurgeJobTest extends DatabaseTest {

    @Autowired
    FailedStartPurgeJob job;

    @Autowired
    AccountService accounts;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    private void log(UserId user, Instant at) {
        jdbc.sql("INSERT INTO failed_start_log (user_id, local_date, kind, at) VALUES (:u, :d, '5xx', :at)")
                .param("u", user.value())
                .param("d", LocalDate.ofInstant(at, ZoneOffset.UTC))
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .update();
    }

    @Test
    void removesOnlyRowsOlderThanTheRetentionAndAnswersHowMany() {
        UserId user = accounts.resolve(
                "user_" + UUID.randomUUID().toString().replace("-", ""),
                new NewAccountContext("device-" + UUID.randomUUID(), "android", "10.9.2.1"));
        clock.advance(Duration.between(clock.instant(), Instant.parse("2026-11-03T10:00:00Z")));
        log(user, clock.instant().minus(Duration.ofDays(91)));
        log(user, clock.instant().minus(Duration.ofDays(92)));
        log(user, clock.instant().minus(Duration.ofDays(89)));

        assertThat(job.run()).isEqualTo(2);
        assertThat(job.run()).isZero(); // safe to repeat

        assertThat(jdbc.sql("SELECT count(*) FROM failed_start_log WHERE user_id = :u")
                        .param("u", user.value())
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }
}
