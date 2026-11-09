package app.stageminute.progress.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.progress.DayState;
import app.stageminute.progress.StreakState;
import app.stageminute.progress.StreakView;
import app.stageminute.progress.WeekDay;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The streak from dates alone (03 F-053; 09 EC-006, EC-046, EC-110 to EC-118, EC-147). */
class StreakCalculatorTest {

    private static final LocalDate THU = LocalDate.parse("2026-10-15"); // a Thursday
    private static final int GAP = 7;

    private static LocalDate day(int offset) {
        return THU.plusDays(offset);
    }

    private static StreakView compute(Set<LocalDate> qualifying, Set<LocalDate> frozen, LocalDate today, int best) {
        return StreakCalculator.compute(qualifying, frozen, today, best, GAP);
    }

    @Test
    void threeDaysInARowEndingTodayIsAStreakOfThree() {
        StreakView view = compute(Set.of(day(-2), day(-1), day(0)), Set.of(), THU, 0);

        assertThat(view.current()).isEqualTo(3);
        assertThat(view.practicedToday()).isTrue();
        assertThat(view.best()).isEqualTo(3);
    }

    @Test
    void anOpenTodayKeepsTheStreakAliveAndSaysWhatPracticingMakes() { // "Practice today to make it 13."
        StreakView view = compute(Set.of(day(-3), day(-2), day(-1)), Set.of(), THU, 0);

        assertThat(view.current()).isEqualTo(3);
        assertThat(view.practicedToday()).isFalse();
        assertThat(view.state()).isEqualTo(StreakState.PRACTICE_TODAY);
        assertThat(view.nextStreak()).isEqualTo(4);
    }

    @Test
    void aMissedDayEndsTheStreakAndTheCardSaysHowLongItWas() { // "Your 12-day streak ended. Best: 21 days."
        StreakView view = compute(Set.of(day(-4), day(-3), day(-2)), Set.of(), THU, 21);

        assertThat(view.current()).isZero();
        assertThat(view.state()).isEqualTo(StreakState.STREAK_ENDED);
        assertThat(view.endedLength()).isEqualTo(3);
        assertThat(view.best()).isEqualTo(21);
        assertThat(view.nextStreak()).isNull();
    }

    @Test
    void aBrandNewPersonHasNoStreakAndNoDayOne() {
        StreakView view = compute(Set.of(), Set.of(), THU, 0);

        assertThat(view.state()).isEqualTo(StreakState.NEW_USER);
        assertThat(view.current()).isZero();
        assertThat(view.nextStreak()).isNull();
        assertThat(view.endedLength()).isNull();
        assertThat(view.weekDays()).extracting(WeekDay::state).doesNotContain(DayState.MISSED, DayState.PRACTICED);
    }

    @Test
    void aLastDayOlderThanTheRecentTakesWindowIsABackAfterALongGap() {
        assertThat(compute(Set.of(day(-8)), Set.of(), THU, 1).state()).isEqualTo(StreakState.RETURNING_AFTER_GAP);
        assertThat(compute(Set.of(day(-7)), Set.of(), THU, 1).state()).isEqualTo(StreakState.STREAK_ENDED);
    }

    @Test
    void aThrottledDayIsFrozenSoTheStreakStaysAtThree() { // EC-147: the day is frozen, the streak stays at 3
        StreakView view = compute(Set.of(day(-3), day(-2), day(-1)), Set.of(day(0)), THU, 3);

        assertThat(view.current()).isEqualTo(3);
        assertThat(view.state()).isEqualTo(StreakState.PRACTICE_TODAY);
        assertThat(view.nextStreak()).isEqualTo(4);
        assertThat(view.weekDays())
                .filteredOn(WeekDay::today)
                .extracting(WeekDay::state)
                .containsExactly(DayState.FROZEN);
    }

    @Test
    void anOutageDayInTheMiddleKeepsTheRunAliveAndCountsNothing() { // EC-111
        StreakView view = compute(Set.of(day(-3), day(-2), day(0)), Set.of(day(-1)), THU, 0);

        assertThat(view.current()).isEqualTo(3);
        assertThat(view.practicedToday()).isTrue();
    }

    @Test
    void aFrozenDayAloneIsNotAStreak() {
        StreakView view = compute(Set.of(), Set.of(day(0)), THU, 0);

        assertThat(view.state()).isEqualTo(StreakState.NEW_USER);
        assertThat(view.current()).isZero();
    }

    @Test
    void theBestStreakNeverGoesDownAndGrowsWithALongerRun() {
        assertThat(compute(Set.of(day(0)), Set.of(), THU, 21).best()).isEqualTo(21);
        assertThat(compute(Set.of(day(-20), day(-19), day(-18), day(-17), day(-16), day(0)), Set.of(), THU, 2)
                        .best())
                .isEqualTo(5);
    }

    @Test
    void theWeekStripIsMondayToSundayWithMissedTodayAndUpcoming() { // the 06-API example
        StreakView view = compute(Set.of(day(-3), day(-2)), Set.of(), THU, 0);

        assertThat(view.weekDays()).extracting(WeekDay::date).first().isEqualTo(LocalDate.parse("2026-10-12"));
        assertThat(view.weekDays())
                .extracting(WeekDay::state)
                .containsExactly(
                        DayState.PRACTICED,
                        DayState.PRACTICED,
                        DayState.MISSED,
                        DayState.TODAY,
                        DayState.UPCOMING,
                        DayState.UPCOMING,
                        DayState.UPCOMING);
        assertThat(view.weekDays())
                .extracting(WeekDay::today)
                .containsExactly(false, false, false, true, false, false, false);
    }

    @Test
    void aDayBeforeTheFirstSessionIsNotAMiss() {
        StreakView view = compute(Set.of(day(0)), Set.of(), THU, 0);

        assertThat(view.weekDays())
                .extracting(WeekDay::state)
                .startsWith(DayState.BEFORE_START, DayState.BEFORE_START, DayState.BEFORE_START, DayState.PRACTICED);
    }

    @Test
    void aSundayBelongsToTheWeekThatStartedOnTheMondayBefore() {
        StreakView view = compute(Set.of(), Set.of(), THU.plusDays(3), 0);

        assertThat(view.weekDays()).extracting(WeekDay::date).first().isEqualTo(LocalDate.parse("2026-10-12"));
        assertThat(view.weekDays()).last().extracting(WeekDay::today).isEqualTo(true);
    }

    @Test
    void aZoneChangeWestKeepsTheLaterDateTheQuotaAlreadyCounted() { // F-050: a zone change never creates two dates
        // Thursday was practiced, then the zone moved west and the calendar says Wednesday again.
        StreakView view = compute(Set.of(day(-2), day(-1), day(0)), Set.of(), THU.minusDays(1), 0);

        assertThat(view.current()).isEqualTo(3);
        assertThat(view.practicedToday()).isTrue();
        assertThat(view.weekDays())
                .filteredOn(WeekDay::today)
                .extracting(WeekDay::date)
                .containsExactly(THU);
    }

    @Test
    void aZoneChangeEastThatSkippedADateDoesNotBreakTheStreakWhenThatDateIsFrozen() {
        // Practiced Mon and Tue, the move east skipped Wednesday, today is Thursday and practiced.
        StreakView view = compute(Set.of(day(-3), day(-2), day(0)), Set.of(day(-1)), THU, 0);

        assertThat(view.current()).isEqualTo(3);
    }

    @Test
    void aTakeSentLateStillCountsForItsStartDate() { // EC-046, EC-110: the date is the start date, wherever the
        // result came
        assertThat(compute(Set.of(day(-2), day(-1)), Set.of(), THU, 0).current())
                .isEqualTo(2);
    }

    @Test
    void addingADayThatCountsNeverLowersTheStreak() {
        Random random = new Random(20);
        for (int round = 0; round < 300; round++) {
            Set<LocalDate> days = new HashSet<>();
            Set<LocalDate> frozen = new HashSet<>();
            for (int back = 0; back < 40; back++) {
                if (random.nextInt(10) < 6) {
                    days.add(day(-back));
                } else if (random.nextInt(10) < 2) {
                    frozen.add(day(-back));
                }
            }
            int before = compute(days, frozen, THU, 0).current();
            int extra = -random.nextInt(40);
            days.add(day(extra));
            int after = compute(days, frozen, THU, 0).current();
            assertThat(after).as("round %s, added %s", round, extra).isGreaterThanOrEqualTo(before);
        }
    }
}
