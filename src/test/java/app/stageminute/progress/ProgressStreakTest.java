package app.stageminute.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.stageminute.account.AccountService;
import app.stageminute.account.NewAccountContext;
import app.stageminute.account.ProfileChange;
import app.stageminute.account.ProfileService;
import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.Reason;
import app.stageminute.platform.SpendDecision;
import app.stageminute.platform.UserId;
import app.stageminute.practice.Practice;
import app.stageminute.practice.Practice.Failure;
import app.stageminute.practice.Practice.ReleaseReason;
import app.stageminute.practice.QuotaState;
import app.stageminute.practice.Reservation;
import app.stageminute.practice.internal.FakePlans;
import app.stageminute.practice.internal.SpendGateFake;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The streak from real sessions in real zones with the injected clock (03 F-053; 09 EC-006, EC-046, EC-110 to EC-114,
 * EC-147; #20.1). The pure rules are in {@code StreakCalculatorTest}; here the dates come from the database.
 */
@Import(FakePlans.class)
class ProgressStreakTest extends DatabaseTest {

    private static final String TOPIC = "prep-ele-001";

    @Autowired
    Progress progress;

    @Autowired
    OutageWindows outages;

    @Autowired
    Practice practice;

    @Autowired
    AccountService accounts;

    @Autowired
    ProfileService profiles;

    @Autowired
    SpendGateFake gate;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEventPublisher events;

    @BeforeEach
    void calmGate() {
        gate.reset();
        jdbc.sql("DELETE FROM outage_windows").update();
    }

    // --- helpers ---

    private void setClock(String instant) {
        clock.advance(Duration.between(clock.instant(), Instant.parse(instant)));
    }

    private UserId newUser(String zone) {
        UserId user = accounts.resolve(
                "user_" + UUID.randomUUID().toString().replace("-", ""),
                new NewAccountContext("device-" + UUID.randomUUID(), "android", "10.9.2.1"));
        profiles.patch(user, new ProfileChange(null, null, zone, null));
        return user;
    }

    private UUID reserve(UserId user) {
        Reservation reservation = practice.reserve(user, "t_" + UUID.randomUUID(), TOPIC, null);
        assertThat(reservation.state()).isEqualTo(QuotaState.OK);
        return reservation.sessionId();
    }

    /** A session started at this instant and delivered at once. */
    private UUID practiceAt(UserId user, String instant) {
        setClock(instant);
        UUID session = reserve(user);
        practice.commit(user, session, null);
        return session;
    }

    private int count(String table, UserId user) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE user_id = :u")
                .param("u", user.value())
                .query(Integer.class)
                .single();
    }

    private String freezeReason(UserId user, String date) {
        return jdbc.sql("SELECT reason FROM streak_freezes WHERE user_id = :u AND local_date = CAST(:d AS date)")
                .param("u", user.value())
                .param("d", date)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    // --- days follow the stored zone ---

    @Test
    void aDayThatIs23HoursLongInSpringStillCountsOnceAndTheStreakHoldsThroughTheClockChange() {
        UserId user = newUser("America/New_York");
        practiceAt(user, "2026-03-08T04:30:00Z"); // Sat 7 Mar 23:30 EST
        practiceAt(user, "2026-03-09T03:30:00Z"); // Sun 8 Mar 23:30 EDT: the 23-hour day; already 9 Mar in UTC
        practiceAt(user, "2026-03-10T03:30:00Z"); // Mon 9 Mar 23:30 EDT
        setClock("2026-03-10T03:40:00Z");

        ProgressSnapshot snapshot = progress.snapshot(user);

        assertThat(snapshot.streak().current()).isEqualTo(3);
        assertThat(snapshot.streak().practicedToday()).isTrue();
        assertThat(snapshot.todayLocalDate()).isEqualTo(LocalDate.parse("2026-03-09"));
    }

    @Test
    void aDayThatIs25HoursLongInAutumnStillCountsOnceAndTheStreakHoldsThroughTheClockChange() {
        UserId user = newUser("America/New_York");
        practiceAt(user, "2026-11-01T03:30:00Z"); // Sat 31 Oct 23:30 EDT
        practiceAt(user, "2026-11-02T04:30:00Z"); // Sun 1 Nov 23:30 EST: the 25-hour day
        practiceAt(user, "2026-11-03T04:30:00Z"); // Mon 2 Nov 23:30 EST
        setClock("2026-11-03T04:40:00Z");

        assertThat(progress.snapshot(user).streak().current()).isEqualTo(3);
    }

    @Test
    void aTakeAt2359AndOneAt0001AreTwoDaysAndATakeStartedAt2358CountsForItsStartDate() {
        UserId user = newUser("UTC");
        UUID late = reserveAt(user, "2026-10-28T23:58:00Z");
        setClock("2026-10-29T00:03:00Z"); // the result comes after midnight (EC-110)
        practice.commit(user, late, null);
        practiceAt(user, "2026-10-29T00:05:00Z");

        StreakView streak = progress.snapshot(user).streak();

        assertThat(streak.current()).isEqualTo(2);
        assertThat(streak.weekDays())
                .filteredOn(day -> day.date().equals(LocalDate.parse("2026-10-28")))
                .extracting(WeekDay::state)
                .containsExactly(DayState.PRACTICED);
    }

    private UUID reserveAt(UserId user, String instant) {
        setClock(instant);
        return reserve(user);
    }

    @Test
    void aTakeThatUploadsUpTo48HoursLateCountsForItsStartDate() { // EC-046
        UserId user = newUser("UTC");
        UUID session = reserveAt(user, "2026-10-26T20:00:00Z"); // Monday evening, taken offline
        setClock("2026-10-28T10:00:00Z"); // Wednesday morning, nothing has arrived yet
        assertThat(progress.snapshot(user).streak().state()).isEqualTo(StreakState.NEW_USER);

        practice.commit(user, session, null); // it arrives 38 hours later

        StreakView streak = progress.snapshot(user).streak();
        assertThat(streak.state()).isNotEqualTo(StreakState.NEW_USER);
        assertThat(streak.weekDays())
                .filteredOn(day -> day.date().equals(LocalDate.parse("2026-10-26")))
                .extracting(WeekDay::state)
                .containsExactly(DayState.PRACTICED);
    }

    // --- what counts ---

    @Test
    void aSessionGivenBackAfterOurOwnFailureCountsButAUserSideOrUnconfirmedEndDoesNot() { // F-053
        UserId ours = newUser("UTC");
        setClock("2026-10-28T10:00:00Z");
        assertThat(practice.giveBack(ours, reserve(ours), Failure.SERVER_ERROR)).isTrue();
        assertThat(progress.streak(ours).practicedToday()).isTrue();

        UserId unsure = newUser("UTC");
        practice.giveBack(unsure, reserve(unsure), Failure.CLIENT_TIMEOUT);
        assertThat(progress.streak(unsure).practicedToday()).isFalse();

        UserId noScore = newUser("UTC"); // too quiet, too short, not English: a no-score take (F-037)
        practice.release(noScore, reserve(noScore), ReleaseReason.NO_SCORE);
        assertThat(progress.streak(noScore).practicedToday()).isFalse();

        UserId discarded = newUser("UTC");
        practice.release(discarded, reserve(discarded), ReleaseReason.DISCARD);
        assertThat(progress.streak(discarded).practicedToday()).isFalse();
    }

    @Test
    void aGivenBackSessionAndTheRetryThatIsDeliveredTheSameDayCountOnce() {
        UserId user = newUser("UTC");
        setClock("2026-10-28T08:00:00Z");
        practice.giveBack(user, reserve(user), Failure.SERVER_ERROR);
        setClock("2026-10-28T20:00:00Z");
        practice.commit(user, reserve(user), null);

        assertThat(progress.streak(user).current()).isEqualTo(1);
    }

    @Test
    void aSessionDeliveredAfterItWasReleasedStillCountsForItsStartDate() { // AD-109, EC-114
        UserId user = newUser("UTC");
        UUID session = reserveAt(user, "2026-10-28T10:00:00Z");
        practice.giveBack(user, session, Failure.CLIENT_TIMEOUT);
        assertThat(progress.streak(user).practicedToday()).isFalse();

        practice.commit(user, session, null); // the result was done after all

        assertThat(progress.streak(user).practicedToday()).isTrue();
    }

    // --- freezes ---

    @Test
    void aThrottledStartFreezesThatDayWithNoOperatorAndTheStreakStaysAtThree() { // D-78, EC-147
        UserId user = newUser("UTC");
        practiceAt(user, "2026-10-25T10:00:00Z");
        practiceAt(user, "2026-10-26T10:00:00Z");
        practiceAt(user, "2026-10-27T10:00:00Z");
        setClock("2026-10-28T10:00:00Z");
        gate.setFree(SpendDecision.FREE_PAUSED);

        assertThat(practice.reserve(user, "t_" + UUID.randomUUID(), TOPIC, null).state())
                .isEqualTo(QuotaState.THROTTLED);

        StreakView streak = progress.streak(user);
        assertThat(streak.current()).isEqualTo(3);
        assertThat(streak.practicedToday()).isFalse();
        assertThat(streak.state()).isEqualTo(StreakState.PRACTICE_TODAY);
        assertThat(freezeReason(user, "2026-10-28")).isEqualTo("throttle");
        assertThat(count("failed_start_log", user)).isEqualTo(1);
    }

    @Test
    void aThrottledDayStaysFrozenWhenItHappensAgainAndTomorrowCountsOneMore() {
        UserId user = newUser("UTC");
        practiceAt(user, "2026-10-27T10:00:00Z");
        setClock("2026-10-28T10:00:00Z");
        gate.setFree(SpendDecision.FREE_PAUSED);
        practice.reserve(user, "t_" + UUID.randomUUID(), TOPIC, null);
        practice.reserve(user, "t_" + UUID.randomUUID(), TOPIC, null);
        assertThat(count("streak_freezes", user)).isEqualTo(1); // a second refusal freezes nothing more

        gate.reset();
        practiceAt(user, "2026-10-29T10:00:00Z");

        assertThat(progress.streak(user).current()).isEqualTo(2); // the frozen day bridged, and counted nothing
    }

    @Test
    void anOutageFreezesTheDayOfEveryoneWhoseStartWeLoggedAndMarkingItAgainChangesNothing() { // EC-111
        UserId hit = newUser("UTC");
        UserId bystander = newUser("UTC");
        practiceAt(hit, "2026-10-25T10:00:00Z");
        practiceAt(hit, "2026-10-26T10:00:00Z");
        practiceAt(bystander, "2026-10-25T10:00:00Z");
        setClock("2026-10-27T14:00:00Z");
        events.publishEvent(new Practice.StartRefused(
                hit, LocalDate.parse("2026-10-27"), Practice.StartRefused.Kind.SERVER_ERROR, clock.instant()));
        assertThat(progress.streak(hit).state()).isEqualTo(StreakState.PRACTICE_TODAY); // not yet a freeze row
        assertThat(freezeReason(hit, "2026-10-27")).isNull();

        int frozen = outages.mark(Instant.parse("2026-10-27T13:00:00Z"), Instant.parse("2026-10-27T16:00:00Z"), "ops");
        int again = outages.mark(Instant.parse("2026-10-27T13:00:00Z"), Instant.parse("2026-10-27T16:00:00Z"), "ops");

        assertThat(frozen).isEqualTo(1);
        assertThat(again).isZero();
        assertThat(freezeReason(hit, "2026-10-27")).isEqualTo("outage");
        assertThat(count("streak_freezes", bystander)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM outage_windows")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        setClock("2026-10-28T09:00:00Z"); // the next morning the streak is still alive
        assertThat(progress.streak(hit).current()).isEqualTo(2);
    }

    @Test
    void aFailureLoggedInsideAnOutageWindowThatIsAlreadyMarkedFreezesAtOnce() {
        UserId user = newUser("UTC");
        practiceAt(user, "2026-10-26T10:00:00Z");
        outages.mark(Instant.parse("2026-10-27T00:00:00Z"), Instant.parse("2026-10-27T23:59:59Z"), "ops");
        setClock("2026-10-27T12:00:00Z");

        UUID session = reserve(user);
        assertThat(practice.giveBack(user, session, Failure.ANALYSIS_UNAVAILABLE))
                .isTrue();

        assertThat(freezeReason(user, "2026-10-27")).isEqualTo("outage");
    }

    @Test
    void aFailureOutsideAnyWindowIsOnlyLoggedAndFreezesNothing() {
        UserId user = newUser("UTC");
        setClock("2026-10-27T12:00:00Z");

        practice.giveBack(user, reserve(user), Failure.SERVER_TIMEOUT);

        assertThat(count("failed_start_log", user)).isEqualTo(1);
        assertThat(count("streak_freezes", user)).isZero();
    }

    @Test
    void aMoveEastThatSkipsADateFreezesIt() { // F-050: a zone change never breaks a streak
        UserId user = newUser("Etc/GMT+12"); // UTC-12
        setClock("2026-11-05T10:30:00Z"); // Wed 4 Nov 22:30 there
        UUID session = reserve(user);
        practice.commit(user, session, null);
        setClock("2026-11-05T11:30:00Z"); // 23:30 there, and 01:30 on Fri 6 Nov at UTC+14: Thursday 5 Nov is skipped
        profiles.patch(user, new ProfileChange(null, null, "Pacific/Kiritimati", null));
        assertThat(profiles.get(user).timezone()).isEqualTo("Pacific/Kiritimati");

        StreakView streak = progress.streak(user);

        assertThat(freezeReason(user, "2026-11-05")).isEqualTo("zone");
        assertThat(streak.current()).isEqualTo(1);
        assertThat(streak.state()).isEqualTo(StreakState.PRACTICE_TODAY);
    }

    // --- best streak and milestones ---

    @Test
    void theBestStreakIsKeptAndNeverLowered() {
        UserId user = newUser("UTC");
        practiceAt(user, "2026-10-20T10:00:00Z");
        practiceAt(user, "2026-10-21T10:00:00Z");
        practiceAt(user, "2026-10-22T10:00:00Z");
        setClock("2026-10-22T11:00:00Z");
        assertThat(progress.streak(user).best()).isEqualTo(3);

        setClock("2026-10-28T10:00:00Z"); // the streak broke
        StreakView broken = progress.streak(user);

        assertThat(broken.current()).isZero();
        assertThat(broken.best()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT best_streak FROM users WHERE id = :u")
                        .param("u", user.value())
                        .query(Integer.class)
                        .single())
                .isEqualTo(3);
    }

    @Test
    void aMilestoneIsPendingOnceMarkedSeenOnceAndDoesNotComeBackForALowerStreak() { // F-060, EC-114
        UserId user = newUser("UTC");
        practiceAt(user, "2026-10-20T10:00:00Z");
        practiceAt(user, "2026-10-21T10:00:00Z");
        assertThat(progress.streak(user).milestonePending()).isNull();
        practiceAt(user, "2026-10-22T10:00:00Z");
        assertThat(progress.streak(user).milestonePending()).isEqualTo(3);

        progress.markSeen(user, "milestone_3");
        progress.markSeen(user, "milestone_3"); // safe to repeat
        assertThat(progress.streak(user).milestonePending()).isNull();
        assertThat(count("seen_markers", user)).isEqualTo(1);

        // the streak breaks and climbs back to 3: not a new best, nothing to celebrate again
        practiceAt(user, "2026-10-26T10:00:00Z");
        practiceAt(user, "2026-10-27T10:00:00Z");
        practiceAt(user, "2026-10-28T10:00:00Z");
        StreakView again = progress.streak(user);
        assertThat(again.current()).isEqualTo(3);
        assertThat(again.milestonePending()).isNull();
    }

    @Test
    void markingASeenMilestoneIsRefusedForAnyOtherWordOrAMilestoneNotReached() {
        UserId user = newUser("UTC");
        practiceAt(user, "2026-10-28T10:00:00Z");

        for (String marker : List.of("milestone_5", "milestone_7", "milestone_0", "first_ad_notice", "milestone_x")) {
            assertThatThrownBy(() -> progress.markSeen(user, marker))
                    .as(marker)
                    .isInstanceOfSatisfying(
                            BusinessRefusal.class,
                            refusal -> assertThat(refusal.reason()).isEqualTo(Reason.VALIDATION_FAILED));
        }
        assertThat(count("seen_markers", user)).isZero();
    }

    @Test
    void aBrandNewPersonIsNewUserWithNoDayOneAndZeroTotals() {
        UserId user = newUser("UTC");
        setClock("2026-10-28T10:00:00Z");

        ProgressSnapshot snapshot = progress.snapshot(user);

        assertThat(snapshot.streak().state()).isEqualTo(StreakState.NEW_USER);
        assertThat(snapshot.streak().current()).isZero();
        assertThat(snapshot.totals()).isEqualTo(new Totals(0, 0));
    }

    @Test
    void aLongGapIsBackAfterALongGap() { // EC-118
        UserId user = newUser("UTC");
        practiceAt(user, "2026-10-01T10:00:00Z");
        setClock("2026-10-28T10:00:00Z");

        StreakView streak = progress.streak(user);

        assertThat(streak.state()).isEqualTo(StreakState.RETURNING_AFTER_GAP);
        assertThat(streak.current()).isZero();
    }
}
