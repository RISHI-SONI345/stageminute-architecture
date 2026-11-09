package app.stageminute.platform;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.support.DatabaseTest;
import app.stageminute.support.MutableClock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A scheduled job runs on one server at a time (job_locks). */
class JobLocksTest extends DatabaseTest {

    @Autowired
    JobLocks locks;

    @Autowired
    MutableClock clock;

    private static String jobName() {
        return "job-" + UUID.randomUUID();
    }

    @Test
    void onlyOneOwnerHoldsALockAtATime() {
        String job = jobName();

        assertThat(locks.tryLock(job, Duration.ofMinutes(5), "server-a")).isTrue();
        assertThat(locks.tryLock(job, Duration.ofMinutes(5), "server-b")).isFalse();
    }

    @Test
    void aReleasedLockCanBeTakenByAnotherOwner() {
        String job = jobName();
        locks.tryLock(job, Duration.ofMinutes(5), "server-a");

        locks.release(job, "server-a");

        assertThat(locks.tryLock(job, Duration.ofMinutes(5), "server-b")).isTrue();
    }

    @Test
    void onlyTheOwnerCanRelease() {
        String job = jobName();
        locks.tryLock(job, Duration.ofMinutes(5), "server-a");

        locks.release(job, "server-b");

        assertThat(locks.tryLock(job, Duration.ofMinutes(5), "server-b")).isFalse();
    }

    @Test
    void aLockThatWasNeverReleasedFreesItselfWhenItsTimeRunsOut() {
        String job = jobName();
        locks.tryLock(job, Duration.ofMinutes(5), "server-a");

        clock.advance(Duration.ofMinutes(6)); // server-a crashed

        assertThat(locks.tryLock(job, Duration.ofMinutes(5), "server-b")).isTrue();
    }
}
