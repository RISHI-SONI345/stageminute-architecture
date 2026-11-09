package app.stageminute.analysis.vendor;

/**
 * The time one analysis may take in all (03 F-122: 45 s on the server). The pipeline opens a scope around its two
 * vendor calls; each real client asks how much is left, so a Deepgram retry runs only when time remains and Gemini gets
 * what is left. With no scope open (a test, a one-off call) the caller's own cap applies. Uses the monotonic clock, so
 * a change of the wall clock cannot stretch or cut it; nothing here is stored.
 */
public final class VendorBudget {

    private static final long NANOS_PER_MS = 1_000_000L;
    private static final ThreadLocal<Long> DEADLINE_NANOS = new ThreadLocal<>();

    private VendorBudget() {}

    /** A running budget; closing it puts back whatever budget was open before. */
    public static final class Scope implements AutoCloseable {

        private final Long previous;

        private Scope(Long previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                DEADLINE_NANOS.remove();
            } else {
                DEADLINE_NANOS.set(previous);
            }
        }
    }

    /** Starts a budget of {@code totalMs} for the calls made on this thread until the scope is closed. */
    public static Scope open(long totalMs) {
        Long previous = DEADLINE_NANOS.get();
        DEADLINE_NANOS.set(System.nanoTime() + totalMs * NANOS_PER_MS);
        return new Scope(previous);
    }

    /** Runs the action with a budget of {@code totalMs} open, then puts back whatever was open before. */
    public static void within(long totalMs, Runnable action) {
        Scope scope = open(totalMs);
        try {
            action.run();
        } finally {
            scope.close();
        }
    }

    /** Milliseconds left (never below 0), or {@code capMs} when no budget is open; never more than {@code capMs}. */
    static long remainingMs(long capMs) {
        Long deadline = DEADLINE_NANOS.get();
        if (deadline == null) {
            return capMs;
        }
        long left = (deadline - System.nanoTime()) / NANOS_PER_MS;
        return Math.max(0, Math.min(left, capMs));
    }
}
