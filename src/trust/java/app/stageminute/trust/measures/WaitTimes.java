package app.stageminute.trust.measures;

import app.stageminute.trust.model.Observation;
import app.stageminute.trust.model.TakeCase;
import app.stageminute.trust.stats.Stats;
import java.util.ArrayList;
import java.util.List;

/**
 * The real wait from upload to report (11 section 1: p50 8 s or less, p95 15 s or less; C-68: it decides whether a
 * background job is ever needed). Every run counts, scored or not, because the person waits either way.
 */
public final class WaitTimes {

    private static final double MS_PER_SECOND = 1000.0;
    private static final double MEDIAN = 50;
    private static final double TAIL = 95;

    private WaitTimes() {}

    /**
     * The wait.
     *
     * @param p50Seconds the median, or NaN when there is no run
     * @param p95Seconds the 95th percentile, or NaN
     * @param runs how many runs were timed
     * @param judgeP50Seconds the median of the Gemini labels call alone, or NaN when no run timed it
     * @param judgeP95Seconds the 95th percentile of the Gemini call alone, or NaN
     * @param judgeRuns how many runs reached Gemini and had it timed
     * @param otherP50Seconds the median of the rest of the wait (Deepgram, scoring, our code) on those same runs, or NaN
     * @param otherP95Seconds the 95th percentile of the rest, or NaN
     */
    public record Result(
            double p50Seconds,
            double p95Seconds,
            int runs,
            double judgeP50Seconds,
            double judgeP95Seconds,
            int judgeRuns,
            double otherP50Seconds,
            double otherP95Seconds) {

        /** A result with no separate Gemini timing. */
        public Result(double p50Seconds, double p95Seconds, int runs) {
            this(p50Seconds, p95Seconds, runs, Double.NaN, Double.NaN, 0, Double.NaN, Double.NaN);
        }
    }

    public static Result of(List<TakeCase> cases) {
        List<Double> seconds = new ArrayList<>();
        List<Double> judge = new ArrayList<>();
        List<Double> other = new ArrayList<>();
        for (TakeCase take : cases) {
            for (Observation run : take.runs()) {
                seconds.add(run.waitMs() / MS_PER_SECOND);
                if (run.judgeMs() > 0) {
                    judge.add(run.judgeMs() / MS_PER_SECOND);
                    other.add(Math.max(0, run.waitMs() - run.judgeMs()) / MS_PER_SECOND);
                }
            }
        }
        return new Result(
                Stats.percentile(seconds, MEDIAN),
                Stats.percentile(seconds, TAIL),
                seconds.size(),
                Stats.percentile(judge, MEDIAN),
                Stats.percentile(judge, TAIL),
                judge.size(),
                Stats.percentile(other, MEDIAN),
                Stats.percentile(other, TAIL));
    }
}
