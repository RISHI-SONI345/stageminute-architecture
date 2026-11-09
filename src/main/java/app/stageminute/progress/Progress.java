package app.stageminute.progress;

import app.stageminute.account.AccountCalendar;
import app.stageminute.account.ProfileService;
import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.Reason;
import app.stageminute.platform.SettingKey;
import app.stageminute.platform.SettingList;
import app.stageminute.platform.Settings;
import app.stageminute.platform.UserId;
import app.stageminute.practice.Practice;
import app.stageminute.progress.internal.Milestones;
import app.stageminute.progress.internal.ProgressRepository;
import app.stageminute.progress.internal.StreakCalculator;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * The numbers behind the Progress tab (03 F-053, F-055, F-060). The streak is worked out on every read from the
 * start dates of the sessions that count, so a late upload or a freeze that arrives afterwards changes the next
 * answer and nothing needs repairing. The server is the only clock and counter; the phone computes none of it.
 *
 * <p>This module reads the person's sessions only through {@link Practice} and never reads the plan or the tier.
 */
@Service
public class Progress {

    private static final int MS_PER_MINUTE = 60_000;

    private final Practice practice;
    private final AccountCalendar calendar;
    private final ProfileService profiles;
    private final ProgressRepository repository;
    private final Settings settings;
    private final Clock clock;

    Progress(
            Practice practice,
            AccountCalendar calendar,
            ProfileService profiles,
            ProgressRepository repository,
            Settings settings,
            Clock clock) {
        this.practice = practice;
        this.calendar = calendar;
        this.profiles = profiles;
        this.repository = repository;
        this.settings = settings;
        this.clock = clock;
    }

    /** The streak, the week strip, the pending milestone and the totals in one answer. */
    public ProgressSnapshot snapshot(UserId user) {
        StreakView streak = streak(user);
        LocalDate today = streak.weekDays().stream()
                .filter(WeekDay::today)
                .map(WeekDay::date)
                .findFirst()
                .orElseGet(() -> calendar.today(user));
        return new ProgressSnapshot(today, streak, totals(user));
    }

    /**
     * The streak block. Raises the stored best streak when this read found a higher one (it is never lowered).
     * The milestone is pending only for a streak that is the person's best (see {@link Milestones}).
     */
    public StreakView streak(UserId user) {
        Set<LocalDate> counted = new TreeSet<>(practice.practiceDays(user));
        int storedBest = profiles.bestStreak(user);
        StreakView view = StreakCalculator.compute(
                counted,
                repository.frozenDays(user),
                calendar.today(user),
                storedBest,
                settings.get(SettingKey.REPORT_WINDOW_DAYS));
        if (view.best() > storedBest) {
            profiles.raiseBestStreak(user, view.best());
        }
        Integer pending =
                Milestones.pending(view.current(), view.best(), milestones(), repository.seenMilestones(user));
        return view.withMilestonePending(pending);
    }

    public Totals totals(UserId user) {
        Practice.SessionTotals totals = practice.totals(user);
        long minutes = (totals.speakingMs() + MS_PER_MINUTE / 2) / MS_PER_MINUTE;
        return new Totals(totals.sessions(), (int) minutes);
    }

    /**
     * Marks a milestone seen, for example {@code milestone_7} (06-API {@code POST /me/seen/{marker}}). It and every
     * smaller milestone are written, so none comes back. Safe to repeat.
     *
     * @throws BusinessRefusal {@code validation_failed} for a word that is not a milestone on the list, or for a
     *     milestone the person has not reached
     */
    public void markSeen(UserId user, String marker) {
        Integer days = Milestones.daysOf(marker);
        List<Integer> milestones = milestones();
        if (days == null || !milestones.contains(days) || days > streak(user).best()) {
            throw new BusinessRefusal(Reason.VALIDATION_FAILED, Map.of("fields", List.of("marker")));
        }
        repository.markSeen(user, Milestones.markersUpTo(days, milestones), clock.instant());
    }

    /** The milestone lengths in days, smallest first; a value that is not a whole positive number is ignored. */
    private List<Integer> milestones() {
        return settings.list(SettingList.MILESTONE_DAYS).stream()
                .map(Progress::wholeNumber)
                .filter(days -> days > 0)
                .distinct()
                .sorted()
                .toList();
    }

    private static int wholeNumber(String code) {
        try {
            return Integer.parseInt(code);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
