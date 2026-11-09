package app.stageminute.practice;

import static app.stageminute.support.LedgerAssertions.assertLedgerMatchesCounters;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.stageminute.account.AccountService;
import app.stageminute.account.NewAccountContext;
import app.stageminute.account.ProfileChange;
import app.stageminute.account.ProfileService;
import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.IdentifierHasher;
import app.stageminute.platform.Reason;
import app.stageminute.platform.UserId;
import app.stageminute.practice.Reservation.Source;
import app.stageminute.practice.internal.FakePlans;
import app.stageminute.practice.internal.Plan;
import app.stageminute.practice.internal.PlanPortFake;
import app.stageminute.support.Concurrently;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reserve (ticket #55, 03 F-048 to F-050): one transaction, the conditional update that never reads then
 * writes, one free session a day, the ad-free first session and the account day. Races run as real
 * parallel transactions against Postgres; every test ends with the ledger check.
 */
@Import(FakePlans.class)
class ReserveTest extends DatabaseTest {

    private static final String TOPIC = "prep-ele-001";
    private static final AtomicInteger TAKES = new AtomicInteger();

    @Autowired
    Practice practice;

    @Autowired
    AccountService accounts;

    @Autowired
    ProfileService profiles;

    @Autowired
    PlanPortFake plans;

    @Autowired
    IdentifierHasher hasher;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    // --- helpers ---

    private UserId newUser(String zone, String deviceId) {
        UserId user = accounts.resolve(
                "user_" + UUID.randomUUID().toString().replace("-", ""),
                new NewAccountContext(deviceId, "android", "10.9.2.1"));
        profiles.patch(user, new ProfileChange(null, null, zone, null));
        return user;
    }

    private UserId newUser() {
        return newUser("UTC", newDevice());
    }

    private static String newDevice() {
        return "device-" + UUID.randomUUID();
    }

    private static String newTake() {
        return "t_" + TAKES.incrementAndGet() + "_" + UUID.randomUUID();
    }

    private void setClock(String instant) {
        clock.advance(Duration.between(clock.instant(), Instant.parse(instant)));
    }

    private Reservation reserve(UserId user) {
        return practice.reserve(user, newTake(), TOPIC, null);
    }

    private int sessionRows(UserId user) {
        return jdbc.sql("SELECT count(*) FROM practice_sessions WHERE user_id = :u")
                .param("u", user.value())
                .query(Integer.class)
                .single();
    }

    private int ledgerRows(UserId user) {
        return jdbc.sql("SELECT count(*) FROM quota_events WHERE user_id = :u")
                .param("u", user.value())
                .query(Integer.class)
                .single();
    }

    private int sessionsUsed(UserId user, LocalDate day) {
        return jdbc.sql("SELECT coalesce(max(sessions_used), 0) FROM daily_counters WHERE user_id = :u"
                        + " AND local_date = :d")
                .param("u", user.value())
                .param("d", day)
                .query(Integer.class)
                .single();
    }

    /** Stands in for 11.3's commit: the session is committed and the ledger row is 0 (AD-107). */
    private void markCommitted(UserId user, UUID sessionId, LocalDate day) {
        jdbc.sql("UPDATE practice_sessions SET state = 'committed', committed_at = now() WHERE id = :id")
                .param("id", sessionId)
                .update();
        jdbc.sql("INSERT INTO quota_events (user_id, local_date, kind, delta, session_id, at)"
                        + " VALUES (:u, :d, 'commit', 0, :s, now())")
                .param("u", user.value())
                .param("d", day)
                .param("s", sessionId)
                .update();
    }

    // --- one transaction, one free session a day ---

    @Test
    void theFirstReserveOfTheDayMakesTheCounterRowTheSessionAndTheLedgerRow() {
        setClock("2026-10-28T10:00:00Z");
        UserId user = newUser();

        Reservation reservation = reserve(user);

        assertThat(reservation.state()).isEqualTo(QuotaState.OK);
        assertThat(reservation.sessionId()).isNotNull();
        assertThat(reservation.day()).isEqualTo(LocalDate.parse("2026-10-28"));
        assertThat(reservation.source()).isEqualTo(Source.FREE);
        assertThat(reservation.repeated()).isFalse();
        assertThat(jdbc.sql("SELECT state || ' ' || source || ' ' || style || ' ' || level || ' ' || local_date"
                                + " FROM practice_sessions WHERE id = :id")
                        .param("id", reservation.sessionId())
                        .query(String.class)
                        .single())
                .isEqualTo("reserved free prep elementary 2026-10-28");
        assertThat(jdbc.sql("SELECT kind || ' ' || delta FROM quota_events WHERE session_id = :id")
                        .param("id", reservation.sessionId())
                        .query(String.class)
                        .list())
                .containsExactly("reserve 1");
        assertThat(sessionsUsed(user, reservation.day())).isEqualTo(1);
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void aSecondFreeSessionTheSameDayIsQuotaZeroAndWritesNothing() {
        setClock("2026-10-28T10:00:00Z");
        UserId user = newUser();
        reserve(user);

        Reservation second = reserve(user);

        assertThat(second.state()).isEqualTo(QuotaState.QUOTA_ZERO);
        assertThat(second.sessionId()).isNull();
        assertThat(second.source()).isNull();
        assertThat(sessionRows(user)).isEqualTo(1);
        assertThat(ledgerRows(user)).isEqualTo(1);
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void theNextAccountDayHasANewFreeSession() {
        setClock("2026-10-28T10:00:00Z");
        UserId user = newUser();
        reserve(user);

        setClock("2026-10-29T00:01:00Z");
        Reservation next = reserve(user);

        assertThat(next.state()).isEqualTo(QuotaState.OK);
        assertThat(next.day()).isEqualTo(LocalDate.parse("2026-10-29"));
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void anAdCreditPaysForASecondSession() {
        setClock("2026-10-28T10:00:00Z");
        UserId user = newUser();
        plans.setAdCredits(user, 1);
        reserve(user);

        Reservation second = reserve(user);

        assertThat(second.state()).isEqualTo(QuotaState.OK);
        assertThat(second.source()).isEqualTo(Source.REWARDED);
        assertThat(reserve(user).state()).isEqualTo(QuotaState.QUOTA_ZERO);
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void proGetsTheFairUseCapAndThenProCap() {
        setClock("2026-10-28T10:00:00Z");
        UserId user = newUser();
        plans.setPlan(user, Plan.PRO);

        for (int i = 0; i < 15; i++) {
            Reservation reservation = reserve(user);
            assertThat(reservation.state()).isEqualTo(QuotaState.OK);
            assertThat(reservation.source()).isEqualTo(Source.PRO);
        }
        Reservation sixteenth = reserve(user);

        assertThat(sixteenth.state()).isEqualTo(QuotaState.PRO_CAP);
        assertThat(sessionRows(user)).isEqualTo(15);
        assertLedgerMatchesCounters(jdbc, user);
    }

    // --- races (real transactions, no mocks) ---

    @Test
    void twoParallelReservesForTheLastSessionOnlyOneSucceeds() {
        setClock("2026-10-28T10:00:00Z");
        UserId user = newUser();

        Concurrently.Outcome<Reservation> outcome = Concurrently.run(2, () -> reserve(user));

        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.values())
                .extracting(Reservation::state)
                .containsExactlyInAnyOrder(QuotaState.OK, QuotaState.QUOTA_ZERO);
        assertThat(sessionRows(user)).isEqualTo(1);
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void ofManyThreadsForTheLastFreeSessionExactlyOneWinsAndTheCounterNeverPassesTheCap() {
        setClock("2026-10-28T10:00:00Z");
        UserId user = newUser();

        Concurrently.Outcome<Reservation> outcome = Concurrently.run(8, () -> reserve(user));

        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.values()).filteredOn(r -> r.state() == QuotaState.OK).hasSize(1);
        assertThat(outcome.values())
                .filteredOn(r -> r.state() == QuotaState.QUOTA_ZERO)
                .hasSize(7);
        assertThat(sessionsUsed(user, LocalDate.parse("2026-10-28"))).isEqualTo(1);
        assertThat(sessionRows(user)).isEqualTo(1);
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void parallelFirstReservesOfTheDayWithNoCounterRowYetAllGetTheirTurn() {
        setClock("2026-10-28T10:00:00Z");
        UserId user = newUser(); // no daily_counters row exists: the INSERT ... ON CONFLICT DO NOTHING race
        plans.setAdCredits(user, 3); // cap 4, the size of the test pool: all four run at once

        Concurrently.Outcome<Reservation> outcome = Concurrently.run(4, () -> reserve(user));

        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.values()).extracting(Reservation::state).containsOnly(QuotaState.OK);
        assertThat(sessionsUsed(user, LocalDate.parse("2026-10-28"))).isEqualTo(4);
        assertThat(sessionRows(user)).isEqualTo(4);
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void withSomeSessionsAlreadyCommittedParallelReservesGetOnlyTheRemainingCapacity() {
        setClock("2026-10-28T10:00:00Z");
        LocalDate day = LocalDate.parse("2026-10-28");
        UserId user = newUser();
        plans.setAdCredits(user, 3); // cap 4
        markCommitted(user, reserve(user).sessionId(), day);
        markCommitted(user, reserve(user).sessionId(), day);

        Concurrently.Outcome<Reservation> outcome = Concurrently.run(6, () -> reserve(user));

        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.values()).filteredOn(r -> r.state() == QuotaState.OK).hasSize(2);
        assertThat(sessionsUsed(user, day)).isEqualTo(4);
        assertThat(sessionRows(user)).isEqualTo(4);
        assertLedgerMatchesCounters(jdbc, user);
    }

    // --- the same take sent again ---

    @Test
    void theSameTakeSentAgainReturnsTheStoredSessionAndCountsOnce() {
        setClock("2026-10-28T10:00:00Z");
        UserId user = newUser();
        plans.setAdCredits(user, 2);
        String take = newTake();

        Reservation first = practice.reserve(user, take, TOPIC, null);
        Reservation again = practice.reserve(user, take, TOPIC, null);

        assertThat(again.state()).isEqualTo(QuotaState.OK);
        assertThat(again.sessionId()).isEqualTo(first.sessionId());
        assertThat(again.repeated()).isTrue();
        assertThat(sessionRows(user)).isEqualTo(1);
        assertThat(ledgerRows(user)).isEqualTo(1);
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void theSameTakeSentAgainEvenOverTheCapReturnsTheStoredSession() {
        setClock("2026-10-28T10:00:00Z");
        UserId user = newUser();
        String take = newTake();
        Reservation first = practice.reserve(user, take, TOPIC, null);

        Reservation again = practice.reserve(user, take, TOPIC, null);

        assertThat(again.state()).isEqualTo(QuotaState.OK);
        assertThat(again.sessionId()).isEqualTo(first.sessionId());
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void theSameTakeSentFromParallelRetriesIsCountedOnce() {
        setClock("2026-10-28T10:00:00Z");
        UserId user = newUser();
        plans.setAdCredits(user, 3); // room for four sessions, so only the unique key can stop a double count
        String take = newTake();

        Concurrently.Outcome<Reservation> outcome =
                Concurrently.run(4, () -> practice.reserve(user, take, TOPIC, null));

        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.values()).extracting(Reservation::state).containsOnly(QuotaState.OK);
        assertThat(outcome.values())
                .extracting(Reservation::sessionId)
                .containsOnly(outcome.values().get(0).sessionId());
        assertThat(sessionRows(user)).isEqualTo(1);
        assertThat(sessionsUsed(user, LocalDate.parse("2026-10-28"))).isEqualTo(1);
        assertLedgerMatchesCounters(jdbc, user);
    }

    // --- topic ---

    @Test
    void anUnknownTopicIsRefusedAndWritesNothing() {
        setClock("2026-10-28T10:00:00Z");
        UserId user = newUser();

        assertThatThrownBy(() -> practice.reserve(user, newTake(), "prep-ele-999", null))
                .isInstanceOfSatisfying(
                        BusinessRefusal.class, e -> assertThat(e.reason()).isEqualTo(Reason.VALIDATION_FAILED));

        assertThat(sessionRows(user)).isZero();
        assertThat(ledgerRows(user)).isZero();
        assertLedgerMatchesCounters(jdbc, user);
    }

    // --- the ad-free first session (D-31, EC-008) ---

    @Test
    void aSignUpAt1150PmGetsItsFirstSessionNormallyAndItIsTheAdFreeOne() {
        setClock("2026-10-29T03:50:00Z"); // 11:50 PM on the 28th in New York
        UserId user = newUser("America/New_York", newDevice());

        Reservation first = reserve(user);

        assertThat(first.state()).isEqualTo(QuotaState.OK);
        assertThat(first.day()).isEqualTo(LocalDate.parse("2026-10-28"));
        assertThat(first.firstSession()).isTrue();

        clock.advance(Duration.ofMinutes(15)); // 12:05 AM: a new account day
        Reservation next = reserve(user);

        assertThat(next.state()).isEqualTo(QuotaState.OK);
        assertThat(next.day()).isEqualTo(LocalDate.parse("2026-10-29"));
        assertThat(next.firstSession()).isFalse();
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void whileTheFirstSessionIsReservedButNotCommittedASecondReserveIsNotTheFirstSession() {
        setClock("2026-10-28T10:00:00Z");
        UserId user = newUser();
        plans.setAdCredits(user, 1);

        Reservation first = reserve(user);
        Reservation second = reserve(user);

        assertThat(first.firstSession()).isTrue();
        assertThat(second.state()).isEqualTo(QuotaState.OK);
        assertThat(second.firstSession()).isFalse();
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void afterACommittedSessionTheNextIsNotTheFirstSession() {
        setClock("2026-10-28T10:00:00Z");
        UserId user = newUser();
        markCommitted(user, reserve(user).sessionId(), LocalDate.parse("2026-10-28"));

        setClock("2026-10-29T10:00:00Z");
        Reservation next = reserve(user);

        assertThat(next.firstSession()).isFalse();
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void aNewAccountOnADeviceThatHadItsFirstSessionIsNotTheFirstSession() {
        setClock("2026-10-28T10:00:00Z");
        String device = newDevice();
        newUser("UTC", device);
        jdbc.sql("UPDATE device_flags SET first_session_used = true WHERE device_hash = :h") // 11.3's commit
                .param("h", hasher.hash(device))
                .update();
        UserId secondAccount = newUser("UTC", device);

        Reservation reservation = practice.reserve(secondAccount, newTake(), TOPIC, device);

        assertThat(reservation.state()).isEqualTo(QuotaState.OK);
        assertThat(reservation.firstSession()).isFalse();
        assertLedgerMatchesCounters(jdbc, secondAccount);
    }

    @Test
    void aNewAccountOnAFreshDeviceIsTheFirstSession() {
        setClock("2026-10-28T10:00:00Z");
        String device = newDevice();
        UserId user = newUser("UTC", device);

        assertThat(practice.reserve(user, newTake(), TOPIC, device).firstSession())
                .isTrue();
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void aDeviceNeverSeenBeforeIsTheFirstSession() {
        setClock("2026-10-28T10:00:00Z");
        UserId user = newUser();

        assertThat(practice.reserve(user, newTake(), TOPIC, newDevice()).firstSession())
                .isTrue();
        assertLedgerMatchesCounters(jdbc, user);
    }

    // --- the account day: server clock and stored zone, never the phone ---

    @Test
    void theDayComesFromTheStoredZoneAndTheServerClock() {
        setClock("2026-10-26T20:00:00Z"); // 01:30 on the 27th in India, still the 26th in UTC
        UserId india = newUser("Asia/Kolkata", newDevice());

        assertThat(reserve(india).day()).isEqualTo(LocalDate.parse("2026-10-27"));
        assertLedgerMatchesCounters(jdbc, india);
    }

    @Test
    void aZoneJumpWestNeverGivesASecondFreeSessionInTheSameRealDay() {
        setClock("2026-10-26T20:00:00Z"); // the 27th in India, the 26th in UTC
        UserId user = newUser("Asia/Kolkata", newDevice());
        assertThat(reserve(user).day()).isEqualTo(LocalDate.parse("2026-10-27"));
        jdbc.sql("UPDATE users SET timezone = 'UTC' WHERE id = :id") // the stored zone changes at once
                .param("id", user.value())
                .update();

        Reservation afterJump = reserve(user);

        assertThat(afterJump.state()).isEqualTo(QuotaState.QUOTA_ZERO);
        assertThat(afterJump.day()).isEqualTo(LocalDate.parse("2026-10-27")); // never earlier than the last used day
        assertThat(sessionRows(user)).isEqualTo(1);
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void afterAZoneJumpWestTheNextFreeSessionComesWithTheNextNewDay() {
        setClock("2026-10-26T20:00:00Z");
        UserId user = newUser("Asia/Kolkata", newDevice());
        reserve(user);
        jdbc.sql("UPDATE users SET timezone = 'UTC' WHERE id = :id")
                .param("id", user.value())
                .update();

        setClock("2026-10-27T20:00:00Z"); // the 27th in UTC: that day is already used
        assertThat(reserve(user).state()).isEqualTo(QuotaState.QUOTA_ZERO);

        setClock("2026-10-28T00:01:00Z"); // the 28th in UTC: a day never used
        Reservation next = reserve(user);

        assertThat(next.state()).isEqualTo(QuotaState.OK);
        assertThat(next.day()).isEqualTo(LocalDate.parse("2026-10-28"));
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void aZoneJumpEastUsesTheLaterDayAtOnce() {
        setClock("2026-10-26T20:00:00Z"); // the 26th in UTC, the 27th in India
        UserId user = newUser();
        assertThat(reserve(user).day()).isEqualTo(LocalDate.parse("2026-10-26"));
        jdbc.sql("UPDATE users SET timezone = 'Asia/Kolkata' WHERE id = :id")
                .param("id", user.value())
                .update();

        Reservation afterJump = reserve(user);

        assertThat(afterJump.state()).isEqualTo(QuotaState.OK);
        assertThat(afterJump.day()).isEqualTo(LocalDate.parse("2026-10-27"));
        assertLedgerMatchesCounters(jdbc, user);
    }
}
