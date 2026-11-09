package app.stageminute.trust.measures;

import app.stageminute.trust.model.Observation;
import app.stageminute.trust.model.TakeCase;
import app.stageminute.trust.stats.Stats;
import java.util.ArrayList;
import java.util.List;

/**
 * Repeat-run score spread on the fixed clips (11 section 1: SD of 3 points or less, range within the 7-point noise
 * band). The same recording and the pinned model, several runs: for each clip the sample SD and the range of the totals
 * are read, and the worst clip decides the gate (a clip that wobbles is not rescued by a steady one).
 */
public final class ScoreSpread {

    private ScoreSpread() {}

    /**
     * The spread over the clips.
     *
     * @param worstSd the largest SD of any clip, or NaN when no clip has two scored runs
     * @param worstRange the largest range of any clip, or NaN
     * @param meanSd the mean of the clips' SDs, or NaN
     * @param clips how many clips had two or more scored runs
     */
    public record Result(double worstSd, double worstRange, double meanSd, int clips) {}

    public static Result of(List<TakeCase> clips) {
        List<Double> sds = new ArrayList<>();
        List<Double> ranges = new ArrayList<>();
        for (TakeCase clip : clips) {
            List<Observation> runs = clip.scoredRuns();
            if (runs.size() < 2) {
                continue;
            }
            List<Double> totals = runs.stream().map(run -> (double) run.total()).toList();
            sds.add(Stats.sampleSd(totals));
            ranges.add(Stats.range(totals));
        }
        if (sds.isEmpty()) {
            return new Result(Double.NaN, Double.NaN, Double.NaN, 0);
        }
        return new Result(max(sds), max(ranges), Stats.mean(sds), sds.size());
    }

    private static double max(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
    }
}
