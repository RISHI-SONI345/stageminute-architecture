package app.stageminute.trust.stats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.ToDoubleFunction;

/**
 * A bootstrap range for a statistic. The labelled set has only 10 to 15 speakers, so one number is not enough: the
 * harness resamples the units (speakers, because takes of one speaker are not independent) with replacement, recomputes
 * the statistic each time, and reports the middle 95 % of the results (the percentile method). A fixed random seed makes
 * the report repeatable: the same inputs always give the same range.
 */
public final class Bootstrap {

    public static final int RESAMPLES = 2000;
    public static final double LOW_PERCENT = 2.5;
    public static final double HIGH_PERCENT = 97.5;

    private Bootstrap() {}

    /**
     * The 95 % range of the statistic over resampled units.
     *
     * @param units the things resampled (speakers)
     * @param statistic computes the number from a list of units; NaN when it cannot (such resamples are skipped)
     * @param seed the random seed; the same seed gives the same range
     */
    public static <T> Interval interval(List<T> units, ToDoubleFunction<List<T>> statistic, long seed) {
        if (units.isEmpty()) {
            return Interval.NONE;
        }
        Random random = new Random(seed);
        List<Double> results = new ArrayList<>();
        for (int i = 0; i < RESAMPLES; i++) {
            List<T> sample = new ArrayList<>(units.size());
            for (int j = 0; j < units.size(); j++) {
                sample.add(units.get(random.nextInt(units.size())));
            }
            double value = statistic.applyAsDouble(sample);
            if (!Double.isNaN(value)) {
                results.add(value);
            }
        }
        if (results.isEmpty()) {
            return Interval.NONE;
        }
        Collections.sort(results);
        return new Interval(Stats.percentile(results, LOW_PERCENT), Stats.percentile(results, HIGH_PERCENT));
    }
}
