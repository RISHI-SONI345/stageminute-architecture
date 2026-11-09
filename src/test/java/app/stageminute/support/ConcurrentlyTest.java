package app.stageminute.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The race runner must really run the tasks at the same time, or a race test proves nothing. */
class ConcurrentlyTest {

    @Test
    void allTasksAreInsideAtTheSameTime() {
        CyclicBarrier meet = new CyclicBarrier(4);

        // Run one after another, the first task would wait for the barrier and time out.
        Concurrently.Outcome<Integer> outcome = Concurrently.run(4, () -> {
            meet.await(5, TimeUnit.SECONDS);
            return 1;
        });

        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.values()).hasSize(4);
    }

    @Test
    void aFailingTaskIsCollectedNotThrown() {
        Concurrently.Outcome<Integer> outcome = Concurrently.run(3, () -> {
            throw new IllegalStateException("boom");
        });

        assertThat(outcome.values()).isEmpty();
        assertThat(outcome.failures()).hasSize(3).allMatch(e -> e instanceof IllegalStateException);
    }
}
