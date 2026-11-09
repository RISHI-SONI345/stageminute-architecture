package app.stageminute.practice;

import static app.stageminute.support.LedgerAssertions.assertLedgerMatchesCounters;
import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.account.AccountService;
import app.stageminute.account.NewAccountContext;
import app.stageminute.platform.IdentifierHasher;
import app.stageminute.platform.UserId;
import app.stageminute.practice.Practice.Failure;
import app.stageminute.practice.Practice.ReleaseReason;
import app.stageminute.practice.internal.FakePlans;
import app.stageminute.practice.internal.PlanPortFake;
import app.stageminute.support.Concurrently;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Commit, release and give-back (ticket #56, 03 F-048, D-38, D-72): a scored result commits the session; a
 * release or a give-back returns it to today's count; server-confirmed failures are given back with no cap,
 * unconfirmable ones at most {@code unconfirmed_give_backs_per_day} a day, user-side ones never. Every change
 * writes its ledger row in the same transaction, and doing it twice writes one row.
 */
@Import(FakePlans.class)
class SessionEndTest extends DatabaseTest {

    private static final String TOPIC = "prep-ele-001";
    private static final LocalDate DAY = LocalDate.parse("2026-10-28");

    @Autowired
    Practice practice;

    @Autowired
    AccountService accounts;

    @Autowired
    PlanPortFake plans;

    @Autowired
    IdentifierHasher hasher;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void morning() {
        clock.advance(Duration.between(clock.instant(), Instant.parse("2026-10-28T10:00:00Z")));
    }

    // --- helpers ---

    private UserId newUser(String deviceId) {
        return accounts.resolve(
                "user_" + UUID.randomUUID().toString().replace("-", ""),
                new NewAccountContext(deviceId, "android", "10.9.2.1"));
    }

    private UserId newUser() {
        return newUser(newDevice());
    }

    private static String newDevice() {
        return "device-" + UUID.randomUUID();
    }

    private UUID reserve(UserId user) {
        Reservation reservation = practice.reserve(user, "t_" + UUID.randomUUID(), TOPIC, null);
        assertThat(reservation.state()).isEqualTo(QuotaState.OK);
        return reservation.sessionId();
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

    private int sessionsUsed(UserId user) {
        return jdbc.sql("SELECT sessions_used FROM daily_counters WHERE user_id = :u AND local_date = :d")
                .param("u", user.value())
                .param("d", DAY)
                .query(Integer.class)
                .single();
    }

    private int unconfirmedGiveBacks(UserId user) {
        return jdbc.sql("SELECT unconfirmed_give_backs FROM daily_counters WHERE user_id = :u AND local_date = :d")
                .param("u", user.value())
                .param("d", DAY)
                .query(Integer.class)
                .single();
    }

    private boolean deviceFirstSessionUsed(String device) {
        return jdbc.sql("SELECT coalesce(bool_or(first_session_used), false) FROM device_flags WHERE device_hash = :h")
                .param("h", hasher.hash(device))
                .query(Boolean.class)
                .single();
    }

    // --- commit ---

    @Test
    void aScoredResultCommitsTheSessionWithALedgerRowOfZero() {
        UserId user = newUser();
        UUID session = reserve(user);

        practice.commit(user, session, null);

        assertThat(state(session)).isEqualTo("committed");
        assertThat(ledger(session)).containsExactly("reserve 1", "commit 0");
        assertThat(sessionsUsed(user)).isEqualTo(1);
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void committingTwiceWritesOneLedgerRow() {
        UserId user = newUser();
        UUID session = reserve(user);

        practice.commit(user, session, null);
        practice.commit(user, session, null);

        assertThat(ledger(session)).containsExactly("reserve 1", "commit 0");
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void parallelCommitsOfOneSessionWriteOneLedgerRow() {
        UserId user = newUser();
        UUID session = reserve(user);

        Concurrently.Outcome<Boolean> outcome = Concurrently.run(4, () -> {
            practice.commit(user, session, null);
            return true;
        });

        assertThat(outcome.failures()).isEmpty();
        assertThat(ledger(session)).containsExactly("reserve 1", "commit 0");
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void anotherPersonCannotCommitMySession() {
        UserId me = newUser();
        UserId other = newUser();
        UUID session = reserve(me);

        practice.commit(other, session, null);

        assertThat(state(session)).isEqualTo("reserved");
        assertLedgerMatchesCounters(jdbc, me, other);
    }

    @Test
    void aResultDeliveredAfterAReleaseStillCountsTheSession() {
        UserId user = newUser();
        UUID session = reserve(user);
        practice.release(user, session, ReleaseReason.ABANDON);

        practice.commit(user, session, null);

        assertThat(state(session)).isEqualTo("committed");
        assertThat(sessionsUsed(user)).isEqualTo(1);
        assertThat(ledger(session)).containsExactly("reserve 1", "release -1", "reserve 1", "commit 0");
        assertLedgerMatchesCounters(jdbc, user);
    }

    // --- the ad-free first session is used up at commit (founder, 2026-10-28) ---

    @Test
    void theFirstCommitUsesUpTheFirstSessionOnTheDevice() {
        String device = newDevice();
        UserId user = newUser(device);
        UUID session = reserve(user);

        boolean firstSession = practice.commit(user, session, device);

        assertThat(firstSession).isTrue();
        assertThat(deviceFirstSessionUsed(device)).isTrue();
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void aSecondCommitIsNotTheFirstSession() {
        String device = newDevice();
        UserId user = newUser(device);
        plans.setAdCredits(user, 1);
        practice.commit(user, reserve(user), device);

        assertThat(practice.commit(user, reserve(user), device)).isFalse();
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void aNewAccountOnAUsedDeviceDoesNotGetASecondFirstSession() {
        String device = newDevice();
        UserId first = newUser(device);
        practice.commit(first, reserve(first), device);
        UserId second = newUser(device);

        assertThat(practice.commit(second, reserve(second), device)).isFalse();
        assertLedgerMatchesCounters(jdbc, first, second);
    }

    @Test
    void aReleaseOfAFirstSessionLeavesTheDeviceFlagUnset() {
        String device = newDevice();
        UserId user = newUser(device);
        UUID session = reserve(user);

        practice.release(user, session, ReleaseReason.DISCARD);

        assertThat(deviceFirstSessionUsed(device)).isFalse();
        assertThat(practice.reserve(user, "t_" + UUID.randomUUID(), TOPIC, device)
                        .firstSession())
                .isTrue();
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void aGiveBackOfAFirstSessionLeavesTheDeviceFlagUnset() {
        String device = newDevice();
        UserId user = newUser(device);
        UUID session = reserve(user);

        practice.giveBack(user, session, Failure.SERVER_ERROR);

        assertThat(deviceFirstSessionUsed(device)).isFalse();
        UUID next =
                practice.reserve(user, "t_" + UUID.randomUUID(), TOPIC, device).sessionId();
        assertThat(practice.commit(user, next, device)).isTrue();
        assertLedgerMatchesCounters(jdbc, user);
    }

    // --- release: every reason ---

    @ParameterizedTest
    @EnumSource(ReleaseReason.class)
    void aReleaseReturnsTheSessionToTodayWithALedgerRow(ReleaseReason reason) {
        UserId user = newUser();
        UUID session = reserve(user);

        boolean released = practice.release(user, session, reason);

        assertThat(released).isTrue();
        assertThat(state(session)).isEqualTo("released " + reason.wire());
        assertThat(ledger(session)).containsExactly("reserve 1", "release -1");
        assertThat(sessionsUsed(user)).isZero();
        assertThat(practice.reserve(user, "t_" + UUID.randomUUID(), TOPIC, null).state())
                .isEqualTo(QuotaState.OK);
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void releasingTwiceWritesOneLedgerRow() {
        UserId user = newUser();
        UUID session = reserve(user);

        practice.release(user, session, ReleaseReason.CANCEL);
        boolean again = practice.release(user, session, ReleaseReason.CANCEL);

        assertThat(again).isFalse();
        assertThat(ledger(session)).containsExactly("reserve 1", "release -1");
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void parallelReleasesOfOneSessionWriteOneLedgerRow() {
        UserId user = newUser();
        UUID session = reserve(user);

        Concurrently.Outcome<Boolean> outcome =
                Concurrently.run(4, () -> practice.release(user, session, ReleaseReason.DISCARD));

        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.values()).filteredOn(r -> r).hasSize(1);
        assertThat(ledger(session)).containsExactly("reserve 1", "release -1");
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void aReleaseAfterCommitIsIgnored() {
        UserId user = newUser();
        UUID session = reserve(user);
        practice.commit(user, session, null);

        assertThat(practice.release(user, session, ReleaseReason.DISCARD)).isFalse();

        assertThat(state(session)).isEqualTo("committed");
        assertThat(ledger(session)).containsExactly("reserve 1", "commit 0");
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void aReleaseOfAnUnknownSessionChangesNothing() {
        UserId user = newUser();

        assertThat(practice.release(user, UUID.randomUUID(), ReleaseReason.DISCARD))
                .isFalse();
        assertLedgerMatchesCounters(jdbc, user);
    }

    // --- give-back: every failure kind ---

    @ParameterizedTest
    @EnumSource(
            value = Failure.class,
            names = {"SERVER_ERROR", "SERVER_TIMEOUT", "ANALYSIS_UNAVAILABLE", "CLIENT_TIMEOUT", "UPLOAD_ABANDONED"})
    void aFailureNotOnTheUserSideIsGivenBackWithALedgerRow(Failure failure) {
        UserId user = newUser();
        UUID session = reserve(user);

        boolean givenBack = practice.giveBack(user, session, failure);

        assertThat(givenBack).isTrue();
        assertThat(state(session)).isEqualTo("released " + failure.wire());
        assertThat(ledger(session)).containsExactly("reserve 1", "give_back -1");
        assertThat(sessionsUsed(user)).isZero();
        assertLedgerMatchesCounters(jdbc, user);
    }

    @ParameterizedTest
    @EnumSource(
            value = Failure.class,
            names = {"FILE_TOO_LARGE", "UNSUPPORTED_FORMAT", "CONSENT_OFF"})
    void aUserSideCauseIsNeverGivenBack(Failure failure) {
        UserId user = newUser();
        UUID session = reserve(user);

        boolean givenBack = practice.giveBack(user, session, failure);

        assertThat(givenBack).isFalse();
        assertThat(state(session)).isEqualTo("reserved");
        assertThat(ledger(session)).containsExactly("reserve 1");
        assertThat(sessionsUsed(user)).isEqualTo(1);
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void theThirdUnconfirmableFailureInOneAccountDayIsNotGivenBack() {
        UserId user = newUser();

        assertThat(practice.giveBack(user, reserve(user), Failure.CLIENT_TIMEOUT))
                .isTrue();
        assertThat(practice.giveBack(user, reserve(user), Failure.UPLOAD_ABANDONED))
                .isTrue();
        UUID third = reserve(user);
        boolean thirdGivenBack = practice.giveBack(user, third, Failure.CLIENT_TIMEOUT);

        assertThat(thirdGivenBack).isFalse();
        assertThat(state(third)).isEqualTo("reserved");
        assertThat(ledger(third)).containsExactly("reserve 1");
        assertThat(unconfirmedGiveBacks(user)).isEqualTo(2);
        assertThat(sessionsUsed(user)).isEqualTo(1);
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void confirmedFailuresAreAlwaysGivenBackAndDoNotUseTheUnconfirmedCap() {
        UserId user = newUser();

        for (int i = 0; i < 5; i++) {
            assertThat(practice.giveBack(user, reserve(user), Failure.SERVER_ERROR))
                    .isTrue();
        }
        assertThat(practice.giveBack(user, reserve(user), Failure.CLIENT_TIMEOUT))
                .isTrue();
        assertThat(practice.giveBack(user, reserve(user), Failure.CLIENT_TIMEOUT))
                .isTrue();
        assertThat(practice.giveBack(user, reserve(user), Failure.ANALYSIS_UNAVAILABLE))
                .isTrue();

        assertThat(unconfirmedGiveBacks(user)).isEqualTo(2);
        assertThat(sessionsUsed(user)).isZero();
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void theUnconfirmedCapStartsAgainTheNextAccountDay() {
        UserId user = newUser();
        practice.giveBack(user, reserve(user), Failure.CLIENT_TIMEOUT);
        practice.giveBack(user, reserve(user), Failure.CLIENT_TIMEOUT);
        practice.commit(user, reserve(user), null);

        clock.advance(Duration.ofDays(1));

        assertThat(practice.giveBack(user, reserve(user), Failure.CLIENT_TIMEOUT))
                .isTrue();
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void parallelUnconfirmableFailuresAreGivenBackOnlyUpToTheCap() {
        UserId user = newUser();
        plans.setAdCredits(user, 3); // four sessions reserved at once
        List<UUID> sessions = List.of(reserve(user), reserve(user), reserve(user), reserve(user));
        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();

        Concurrently.Outcome<Boolean> outcome = Concurrently.run(
                4, () -> practice.giveBack(user, sessions.get(next.getAndIncrement()), Failure.CLIENT_TIMEOUT));

        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.values()).filteredOn(r -> r).hasSize(2);
        assertThat(unconfirmedGiveBacks(user)).isEqualTo(2);
        assertThat(sessionsUsed(user)).isEqualTo(2);
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void givingBackTwiceWritesOneLedgerRowAndUsesTheCapOnce() {
        UserId user = newUser();
        UUID session = reserve(user);

        practice.giveBack(user, session, Failure.CLIENT_TIMEOUT);
        boolean again = practice.giveBack(user, session, Failure.CLIENT_TIMEOUT);

        assertThat(again).isFalse();
        assertThat(ledger(session)).containsExactly("reserve 1", "give_back -1");
        assertThat(unconfirmedGiveBacks(user)).isEqualTo(1);
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void aGiveBackAfterCommitIsIgnored() {
        UserId user = newUser();
        UUID session = reserve(user);
        practice.commit(user, session, null);

        assertThat(practice.giveBack(user, session, Failure.SERVER_ERROR)).isFalse();

        assertThat(ledger(session)).containsExactly("reserve 1", "commit 0");
        assertLedgerMatchesCounters(jdbc, user);
    }
}
