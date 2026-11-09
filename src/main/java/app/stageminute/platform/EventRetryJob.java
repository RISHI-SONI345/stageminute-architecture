package app.stageminute.platform;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tries a failed event listener again (AD-87, 03 F-094). Spring Modulith leaves a listener that threw as "failed" and
 * does nothing more, so this job hands every failed one back every few minutes (one server at a time, {@link
 * JobLocks}). Listeners are safe to repeat, so a second try is never harmful. It then looks for work that has not
 * finished in {@code job_alert_hours} and, when there is any, logs an error and reports it, so a deletion stuck at a
 * vendor is seen (the log line has counts, never ids).
 */
@Component
public class EventRetryJob {

    static final String LOCK_NAME = "platform.event_retry";

    private static final Logger LOG = LoggerFactory.getLogger(EventRetryJob.class);

    private final FailedEventPublications failed;
    private final EventPublicationLog log;
    private final JobLocks locks;
    private final ErrorReporter errors;
    private final Settings settings;
    private final Duration lockFor;
    private final String owner = "event-retry-" + UUID.randomUUID();

    EventRetryJob(
            FailedEventPublications failed,
            EventPublicationLog log,
            JobLocks locks,
            ErrorReporter errors,
            Settings settings,
            @Value("${stageminute.jobs.event-retry.lock-for}") Duration lockFor) {
        this.failed = failed;
        this.log = log;
        this.locks = locks;
        this.errors = errors;
        this.settings = settings;
        this.lockFor = lockFor;
    }

    /** Runs on the schedule in {@code stageminute.jobs.event-retry.every}; answers how many events are overdue. */
    @Scheduled(fixedDelayString = "${stageminute.jobs.event-retry.every}")
    public int run() {
        if (!locks.tryLock(LOCK_NAME, lockFor, owner)) {
            return 0; // another server runs it now
        }
        try {
            failed.resubmit(ResubmissionOptions.defaults());
            List<EventPublicationLog.Incomplete> overdue = log.overdue();
            if (!overdue.isEmpty()) {
                int maxAttempts = settings.get(SettingKey.EVENT_RETRY_ALERT_ATTEMPTS);
                long tooManyTries =
                        overdue.stream().filter(e -> e.attempts() > maxAttempts).count();
                long deletions = overdue.stream()
                        .filter(EventPublicationLog.Incomplete::isDeletion)
                        .count();
                LOG.error(
                        "events.overdue count={} deletions={} too_many_tries={}",
                        overdue.size(),
                        deletions,
                        tooManyTries);
                errors.report(new IllegalStateException("events overdue: " + overdue.size()));
            }
            return overdue.size();
        } finally {
            locks.release(LOCK_NAME, owner);
        }
    }
}
