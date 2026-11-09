package app.stageminute.practice.jobs;

import app.stageminute.platform.JobLocks;
import app.stageminute.practice.Practice;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Releases reservations that got no result 48 h after they were made (03 F-048, D-72, EC-042). One server runs
 * it at a time ({@link JobLocks}); the release itself is one statement, so even two servers that both ran it
 * would release each session once. Each server has its own lock owner.
 */
@Component
public class StaleReservationJob {

    static final String LOCK_NAME = "practice.stale_reservations";

    private final Practice practice;
    private final JobLocks locks;
    private final Duration lockFor;
    private final String owner = "stale-reservations-" + UUID.randomUUID();

    StaleReservationJob(
            Practice practice,
            JobLocks locks,
            @Value("${stageminute.jobs.stale-reservations.lock-for}") Duration lockFor) {
        this.practice = practice;
        this.locks = locks;
        this.lockFor = lockFor;
    }

    /** Runs on the schedule in {@code stageminute.jobs.stale-reservations.every}; answers how many it released. */
    @Scheduled(fixedDelayString = "${stageminute.jobs.stale-reservations.every}")
    public int run() {
        if (!locks.tryLock(LOCK_NAME, lockFor, owner)) {
            return 0; // another server runs it now
        }
        try {
            return practice.releaseStaleReservations();
        } finally {
            locks.release(LOCK_NAME, owner);
        }
    }
}
