package app.stageminute.progress.internal;

import app.stageminute.progress.DayState;
import app.stageminute.progress.StreakState;
import app.stageminute.progress.StreakView;
import app.stageminute.progress.WeekDay;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The streak from dates alone (03 F-053). Pure: no clock, no database, no settings; the caller passes today's date in
 * the account's zone, so a test can set any day.
 *
 * <p>A streak is a run of consecutive account days. A qualifying day counts one. A frozen day (we failed the person:
 * outage or cost pause) keeps the run alive and counts nothing, so "the streak stays at 3" (EC-147). Today does not
 * break a run that is open: the run stays alive until the day is over.
 *
 * <p>The anchor is the later of today and the latest known day. They differ only after a zone change to the west
 * (F-050 "a zone change never creates two dates"): the quota already counted the later date, so the streak does too.
 */
public final class StreakCalculator {

    private static final int WEEK = 7;

    private StreakCalculator() {}

    /**
     * Works out the streak card from dates alone.
     *
     * @param qualifying the distinct start dates of sessions that count
     * @param frozen the dates frozen for this person
     * @param today today's date in the account's zone
     * @param storedBest the best streak kept so far; the result is never lower
     * @param longGapDays a last counted day older than this many days is a "long gap" (the recent-takes window)
     */
    public static StreakView compute(
            Set<LocalDate> qualifying, Set<LocalDate> frozen, LocalDate today, int storedBest, int longGapDays) {
        TreeSet<LocalDate> counted = new TreeSet<>(qualifying);
        TreeSet<LocalDate> known = new TreeSet<>(counted);
        known.addAll(frozen);

        LocalDate anchor = known.isEmpty() || today.isAfter(known.last()) ? today : known.last();
        boolean practicedToday = counted.contains(anchor);

        int current = runCount(counted, known, openRunEnd(known, anchor));
        int best = Math.max(storedBest, longestRun(counted, known));
        @Nullable LocalDate last = counted.isEmpty() ? null : counted.last();

        List<WeekDay> week = week(anchor, counted, frozen, counted.isEmpty() ? null : counted.first());
        if (last == null) {
            return new StreakView(0, best, false, week, StreakState.NEW_USER, null, null, null);
        }
        if (practicedToday) {
            return new StreakView(current, best, true, week, StreakState.PRACTICED_TODAY, null, null, null);
        }
        if (current > 0) {
            return new StreakView(current, best, false, week, StreakState.PRACTICE_TODAY, null, current + 1, null);
        }
        if (ChronoUnit.DAYS.between(last, anchor) > longGapDays) {
            return new StreakView(0, best, false, week, StreakState.RETURNING_AFTER_GAP, null, null, null);
        }
        return new StreakView(
                0, best, false, week, StreakState.STREAK_ENDED, runCount(counted, known, last), null, null);
    }

    /** The last day of the run that is still open: the anchor, or the day before it; null when neither is known. */
    private static @Nullable LocalDate openRunEnd(SortedSet<LocalDate> known, LocalDate anchor) {
        if (known.contains(anchor)) {
            return anchor;
        }
        LocalDate yesterday = anchor.minusDays(1);
        return known.contains(yesterday) ? yesterday : null;
    }

    /** How many qualifying days sit in the run of consecutive known days that ends on {@code end}. */
    private static int runCount(SortedSet<LocalDate> counted, SortedSet<LocalDate> known, @Nullable LocalDate end) {
        if (end == null) {
            return 0;
        }
        int count = 0;
        for (LocalDate day = end; known.contains(day); day = day.minusDays(1)) {
            if (counted.contains(day)) {
                count++;
            }
        }
        return count;
    }

    private static int longestRun(SortedSet<LocalDate> counted, SortedSet<LocalDate> known) {
        int longest = 0;
        int run = 0;
        LocalDate before = null;
        for (LocalDate day : known) {
            run = before != null && before.plusDays(1).equals(day) ? run : 0;
            if (counted.contains(day)) {
                run++;
            }
            longest = Math.max(longest, run);
            before = day;
        }
        return longest;
    }

    /** Monday to Sunday of the week that holds the anchor (the boards show Mon, Tue, ... Today). */
    private static List<WeekDay> week(
            LocalDate anchor, Set<LocalDate> counted, Set<LocalDate> frozen, @Nullable LocalDate firstCounted) {
        LocalDate monday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<WeekDay> days = new ArrayList<>(WEEK);
        for (int i = 0; i < WEEK; i++) {
            LocalDate date = monday.plusDays(i);
            days.add(new WeekDay(date, dayState(date, anchor, counted, frozen, firstCounted), date.equals(anchor)));
        }
        return Collections.unmodifiableList(days);
    }

    private static DayState dayState(
            LocalDate date,
            LocalDate anchor,
            Set<LocalDate> counted,
            Set<LocalDate> frozen,
            @Nullable LocalDate firstCounted) {
        if (counted.contains(date)) {
            return DayState.PRACTICED;
        }
        if (frozen.contains(date)) {
            return DayState.FROZEN;
        }
        if (date.isAfter(anchor)) {
            return DayState.UPCOMING;
        }
        if (date.equals(anchor)) {
            return DayState.TODAY;
        }
        return firstCounted == null || date.isBefore(firstCounted) ? DayState.BEFORE_START : DayState.MISSED;
    }
}
