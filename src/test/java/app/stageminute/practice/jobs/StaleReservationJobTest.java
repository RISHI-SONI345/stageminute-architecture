package app.stageminute.practice.jobs;

import static app.stageminute.support.LedgerAssertions.assertLedgerMatchesCounters;
import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.account.AccountService;
import app.stageminute.account.NewAccountContext;
import app.stageminute.platform.JobLocks;
import app.stageminute.platform.UserId;
import app.stageminute.practice.Practice;
import app.stageminute.practice.Reservation;
import app.stageminute.support.Concurrently;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The 48 h release job (ticket #59, 03 F-048, D-72, EC-042): a reservation with no result is released 48 h
 * after it was made, with one {@code expire} ledger row, by one bulk statement under a single-run lock. The
 * table holds rows of other test classes too, so every check looks at this test's own sessions.
 */
class StaleReservationJobTest extends DatabaseTest {

    private static final String TOPIC = "prep-ele-001";

    @Autowired
    StaleReservationJob job;

    @Autowired
    Practice practice;

    @Autowired
    JobLocks locks;

    @Autowired
    AccountService accounts;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    private Instant start;

    @BeforeEach
    void morning() {
        // Each test starts later than the last one ended, so reservations of an earlier test are already stale.
        clock.advance(Duration.ofDays(10));
        start = clock.instant();
    }

    private UserId newUser() {
        return accounts.resolve(
                "user_" + UUID.randomUUID().toString().replace("-", ""),
                new NewAccountContext("device-" + UUID.randomUUID(), "android", "10.9.2.1"));
    }

    private UUID reserve(UserId user) {
        Reservation reservation = practice.reserve(user, "t_" + UUID.randomUUID(), TOPIC, null);
        assertThat(reservation.sessionId()).isNotNull();
        return reservation.sessionId();
    }

    private void at(Duration sinceStart) {
        clock.advance(Duration.between(clock.instant(), start.plus(sinceStart)));
    }

    private String state(UUID session) {
        return jdbc.sql("SELECT state || coalesce(' ' || release_reason, '') FROM practice_sessions WHERE id = :id")
                .param("id", session)
                .query(String.class)
                .single();
    }

    private List<String> ledger(UUID session) {
        return jdbc.sql("SELECT kind || ' ' || delta FROM quota_events WHERE session_id = :id ORDER BY id")
                .param("id", session)
                .query(String.class)
                .list();
    }

    @Test
    void aReservationAt47h59mStaysAndAt48hIsReleasedWithALedgerRow() {
        UserId user = newUser();
        UUID session = reserve(user);

        at(Duration.ofHours(47).plusMinutes(59));
        job.run();
        assertThat(state(session)).isEqualTo("reserved");

        at(Duration.ofDays(2)); // exactly 48 h
        job.run();
        assertThat(state(session)).isEqualTo("released timeout_48h");
        assertThat(ledger(session)).containsExactly("reserve 1", "expire -1");
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void theReleaseGivesTheSessionBackOnTheDayItCountedOn() {
        UserId user = newUser();
        reserve(user);

        at(Duration.ofDays(2)); // exactly 48 h
        job.run();

        assertThat(jdbc.sql("SELECT sessions_used FROM daily_counters WHERE user_id = :u")
                        .param("u", user.value())
                        .query(Integer.class)
                        .single())
                .isZero();
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void aTimeoutSetBelowOneHourReleasesNothing() {
        // A slip in the settings table (0) must not free every reservation each run: that would hand out sessions.
        jdbc.sql("INSERT INTO app_settings (key, value, version, updated_at, updated_by)"
                        + " VALUES ('reservation_timeout_hours', '0'::jsonb, 1, :t, 'test')")
                .param("t", java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC))
                .update();
        try {
            UserId user = newUser();
            UUID session = reserve(user);
            at(Duration.ofHours(1));

            assertThat(job.run()).isZero();
            assertThat(state(session)).isEqualTo("reserved");
            assertLedgerMatchesCounters(jdbc, user);
        } finally {
            jdbc.sql("DELETE FROM app_settings WHERE key = 'reservation_timeout_hours'")
                    .update();
        }
    }

    @Test
    void runningTheJobTwiceReleasesOnce() {
        UserId user = newUser();
        UUID session = reserve(user);
        at(Duration.ofHours(49));

        job.run();
        job.run();

        assertThat(ledger(session)).containsExactly("reserve 1", "expire -1");
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void twoInstancesAtOnceReleaseOnce() {
        UserId first = newUser();
        UserId second = newUser();
        UUID a = reserve(first);
        UUID b = reserve(second);
        at(Duration.ofHours(49));
        StaleReservationJob otherInstance =
                new StaleReservationJob(practice, locks, Duration.ofMinutes(10)); // a second server
        AtomicInteger next = new AtomicInteger();

        Concurrently.Outcome<Integer> outcome =
                Concurrently.run(2, () -> (next.getAndIncrement() == 0 ? job : otherInstance).run());

        assertThat(outcome.failures()).isEmpty();
        assertThat(ledger(a)).containsExactly("reserve 1", "expire -1");
        assertThat(ledger(b)).containsExactly("reserve 1", "expire -1");
        assertLedgerMatchesCounters(jdbc, first, second);
    }

    @Test
    void whileAnotherServerHoldsTheLockNothingIsReleased() {
        UserId user = newUser();
        UUID session = reserve(user);
        at(Duration.ofHours(49));
        assertThat(locks.tryLock(StaleReservationJob.LOCK_NAME, Duration.ofMinutes(5), "other-server"))
                .isTrue();

        int released = job.run();

        assertThat(released).isZero();
        assertThat(state(session)).isEqualTo("reserved");
        locks.release(StaleReservationJob.LOCK_NAME, "other-server");
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void committedAndReleasedSessionsAreLeftAlone() {
        UserId user = newUser();
        jdbc.sql("UPDATE users SET timezone = 'UTC' WHERE id = :id")
                .param("id", user.value())
                .update();
        UUID committed = reserve(user);
        practice.commit(user, committed, null);
        at(Duration.ofDays(1));
        UUID released = reserve(user);
        practice.release(user, released, Practice.ReleaseReason.DISCARD);

        at(Duration.ofDays(4));
        job.run();

        assertThat(ledger(committed)).containsExactly("reserve 1", "commit 0");
        assertThat(ledger(released)).containsExactly("reserve 1", "release -1");
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void manyStaleReservationsAreReleasedInOneRunEachWithItsOwnRow() {
        UserId user = newUser();
        jdbc.sql("UPDATE users SET timezone = 'UTC' WHERE id = :id")
                .param("id", user.value())
                .update();
        UUID first = reserve(user);
        at(Duration.ofDays(1));
        UUID second = reserve(user); // 24 h later: the next account day, its own counter row
        at(Duration.ofDays(3));

        job.run();

        assertThat(ledger(first)).containsExactly("reserve 1", "expire -1");
        assertThat(ledger(second)).containsExactly("reserve 1", "expire -1");
        assertLedgerMatchesCounters(jdbc, user);
    }
}
