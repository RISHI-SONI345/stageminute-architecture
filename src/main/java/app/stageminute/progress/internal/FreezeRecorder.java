package app.stageminute.progress.internal;

import app.stageminute.account.ProfileService;
import app.stageminute.practice.Practice;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Freezes the days we are to blame for (03 F-053, D-78). A frozen day keeps the streak alive and counts nothing.
 *
 * <ul>
 *   <li>A start refused with {@code throttled} (the AI-cost pause) freezes that day at once, with no operator.
 *   <li>A start or a take we failed with a 5xx or "unavailable" is logged; the day is frozen when it falls inside an
 *       outage window the operator marked, now or later ({@code OutageWindows}).
 *   <li>A move to the east that skipped a calendar date freezes the skipped dates, so a zone change never breaks a
 *       streak (F-050).
 * </ul>
 *
 * Only opaque ids, dates and the kind are stored: no words, no topic.
 */
@Component
public class FreezeRecorder {

    private final ProgressRepository repository;

    FreezeRecorder(ProgressRepository repository) {
        this.repository = repository;
    }

    @EventListener
    @Transactional
    void onStartRefused(Practice.StartRefused event) {
        repository.logFailedStart(event.user(), event.day(), event.kind().wire(), event.at());
        if (event.kind() == Practice.StartRefused.Kind.THROTTLED) {
            repository.freeze(event.user(), event.day(), "throttle");
        } else if (repository.insideOutageWindow(event.at())) {
            repository.freeze(event.user(), event.day(), "outage");
        }
    }

    /** Runs inside the profile change's transaction: the new zone and its frozen dates happen together. */
    @EventListener
    void onZoneChanged(ProfileService.ZoneChanged event) {
        LocalDate before = LocalDate.ofInstant(event.at(), ZoneId.of(event.from()));
        LocalDate after = LocalDate.ofInstant(event.at(), ZoneId.of(event.to()));
        for (LocalDate skipped = before.plusDays(1); skipped.isBefore(after); skipped = skipped.plusDays(1)) {
            repository.freeze(event.user(), skipped, "zone");
        }
    }
}
