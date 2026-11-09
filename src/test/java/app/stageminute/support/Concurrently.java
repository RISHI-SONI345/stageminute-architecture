package app.stageminute.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs the same task on several threads that are all released at the same moment, so a test can prove a
 * race is safe against the real database (ticket #11: separate transactions, no mocks). Every task that
 * fails is collected, never thrown, so a test can assert "exactly one wins and nobody crashes".
 */
public final class Concurrently {

    private static final long WAIT_SECONDS = 60;

    /** What the tasks returned and what the failed ones threw. */
    public record Outcome<T>(List<T> values, List<Throwable> failures) {}

    private Concurrently() {}

    public static <T> Outcome<T> run(int threads, Callable<T> task) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            if (!ready.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The threads did not all start");
            }
            go.countDown();
            List<T> values = new ArrayList<>();
            List<Throwable> failures = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    values.add(future.get(WAIT_SECONDS, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                } catch (TimeoutException e) {
                    failures.add(e);
                }
            }
            return new Outcome<>(values, failures);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }
}
