package app.stageminute.progress.jobs;

import app.stageminute.platform.JobLocks;
import app.stageminute.platform.SettingKey;
import app.stageminute.platform.Settings;
import app.stageminute.progress.internal.ProgressRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes {@code failed_start_log} rows older than {@code failed_start_retention_days} (90; 03 section 3). Only the
 * outage freeze reads that table. One server runs it at a time ({@link JobLocks}).
 */
@Component
public class FailedStartPurgeJob {

    static final String LOCK_NAME = "progress.failed_start_purge";

    private final ProgressRepository repository;
    private final Settings settings;
    private final Clock clock;
    private final JobLocks locks;
    private final Duration lockFor;
    private final String owner = "failed-start-purge-" + UUID.randomUUID();

    FailedStartPurgeJob(
            ProgressRepository repository,
            Settings settings,
            Clock clock,
            JobLocks locks,
            @Value("${stageminute.jobs.failed-start-purge.lock-for}") Duration lockFor) {
        this.repository = repository;
        this.settings = settings;
        this.clock = clock;
        this.locks = locks;
        this.lockFor = lockFor;
    }

    /** Runs on the schedule in {@code stageminute.jobs.failed-start-purge.every}; answers how many rows it removed. */
    @Scheduled(fixedDelayString = "${stageminute.jobs.failed-start-purge.every}")
    public int run() {
        if (!locks.tryLock(LOCK_NAME, lockFor, owner)) {
            return 0;
        }
        try {
            int days = settings.get(SettingKey.FAILED_START_RETENTION_DAYS);
            if (days < 1) {
                return 0; // a slip in the settings must not empty the table
            }
            return repository.purgeFailedStartsBefore(clock.instant().minus(Duration.ofDays(days)));
        } finally {
            locks.release(LOCK_NAME, owner);
        }
    }
}
