/**
 * Sessions, attempts, the quota ledger and every daily cap.
 * <p>Start here: {@code Practice} (reserve, commit, release, giveBack, quota; beginAnalysis, recordScoredAttempt and the end of an analysis, C-52; {@code Practice.ReleaseReason}, {@code Practice.Failure}; {@code Practice.StartRefused} (event for {@code progress}); {@code practiceDays} and {@code totals}, what {@code progress} reads; reserve and quota ask the platform {@code SpendGate} first, so a paused free person reserves nothing), {@code Reservation}, {@code Quota}, {@code QuotaState}, {@code web/SessionsController}, {@code web/NextTopicController}, {@code jobs/StaleReservationJob}, {@code AttemptRule}, {@code AttemptSettings}, {@code NextAttempt}; the cap rule is {@code internal/AllowanceRule}; {@code internal/DailyCapsAdapter} answers the platform {@code DailyCaps} port (disputes); the next topic is {@code internal/NextTopicService}. Map: ARCHITECTURE.md.
 * <p>Allowed dependencies follow docs/architecture/02-SERVER-LLD.md §1; Spring Modulith verify() enforces them.
 */
@ApplicationModule(
        displayName = "practice",
        allowedDependencies = {"account", "money", "topics", "platform"})
package app.stageminute.practice;

import org.springframework.modulith.ApplicationModule;
