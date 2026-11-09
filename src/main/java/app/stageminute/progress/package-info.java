/**
 * Streak, totals, milestones, frozen days.
 * <p>Start here: {@code Progress} (the snapshot for the Progress tab: streak, week strip, totals, pending milestone; {@code markSeen}), {@code ProgressSnapshot}, {@code StreakView}, {@code WeekDay}, {@code Totals}, {@code StreakState}, {@code DayState}, {@code OutageWindows} (the operator marks an outage; #27 calls it), {@code internal/StreakCalculator} (pure: dates in, streak out), {@code internal/Milestones} (pure), {@code internal/FreezeRecorder} (listens to {@code Practice.StartRefused} and {@code ProfileService.ZoneChanged}), {@code internal/ProgressRepository}, {@code web/ProgressController} ({@code GET /v1/me/progress}, {@code POST /v1/me/seen/{marker}}), {@code jobs/FailedStartPurgeJob}. Map: ARCHITECTURE.md.
 * <p>Allowed dependencies follow docs/architecture/02-SERVER-LLD.md §1; Spring Modulith verify() enforces them.
 */
@ApplicationModule(
        displayName = "progress",
        allowedDependencies = {"practice", "account", "platform"})
package app.stageminute.progress;

import org.springframework.modulith.ApplicationModule;
