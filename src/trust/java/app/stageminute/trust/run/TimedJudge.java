package app.stageminute.trust.run;

import app.stageminute.analysis.vendor.Judge;
import app.stageminute.analysis.vendor.JudgeInput;
import app.stageminute.analysis.vendor.JudgeLabels;
import app.stageminute.analysis.vendor.JudgeVersions;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * A second stopwatch for the harness (#19.3): it times only the labels call to Gemini, so the report can tell the
 * model's own time from the whole wait (which also holds Deepgram). It wraps the real judge, changes nothing it does
 * and keeps no words: only a running total of nanoseconds, handed out and reset by {@link #drainMs()}.
 */
public final class TimedJudge implements Judge {

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final Judge delegate;
    private final LongSupplier nanoClock;
    private final AtomicLong nanos = new AtomicLong();

    public TimedJudge(Judge delegate, LongSupplier nanoClock) {
        this.delegate = delegate;
        this.nanoClock = nanoClock;
    }

    @Override
    public JudgeLabels judge(JudgeInput input) {
        long started = nanoClock.getAsLong();
        try {
            return delegate.judge(input);
        } finally {
            nanos.addAndGet(nanoClock.getAsLong() - started);
        }
    }

    @Override
    public JudgeVersions versions() {
        return delegate.versions();
    }

    /** The time spent in the judge since the last call, in milliseconds; 0 when it was not called. Resets it. */
    public long drainMs() {
        return nanos.getAndSet(0) / NANOS_PER_MILLI;
    }
}
