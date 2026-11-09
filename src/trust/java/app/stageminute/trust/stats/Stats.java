package app.stageminute.trust.stats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Plain statistics the gates need. Every function states what it does with an empty list: it never guesses. */
public final class Stats {

    private Stats() {}

    /** The mean, or NaN for no values. */
    public static double mean(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    /** The sample standard deviation (divides by n - 1); 0 for one value, NaN for none. */
    public static double sampleSd(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        if (values.size() == 1) {
            return 0.0;
        }
        double mean = mean(values);
        double squares = 0;
        for (double value : values) {
            squares += (value - mean) * (value - mean);
        }
        return Math.sqrt(squares / (values.size() - 1));
    }

    /** Largest minus smallest; NaN for none. */
    public static double range(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        return Collections.max(values) - Collections.min(values);
    }

    /**
     * The value at a percentile by the nearest-rank rule: the smallest value with at least {@code percent} % of the
     * values at or below it. NaN for none.
     */
    public static double percentile(List<Double> values, double percent) {
        if (percent <= 0 || percent > 100) {
            throw new IllegalArgumentException("percent must be above 0 and at most 100, was " + percent);
        }
        if (values.isEmpty()) {
            return Double.NaN;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(percent / 100.0 * sorted.size());
        return sorted.get(Math.max(rank, 1) - 1);
    }

    /** Pearson correlation of two equal-length lists; NaN when fewer than 2 pairs or either side does not vary. */
    public static double pearson(List<Double> x, List<Double> y) {
        if (x.size() != y.size()) {
            throw new IllegalArgumentException("two lists must be the same length: " + x.size() + " and " + y.size());
        }
        if (x.size() < 2) {
            return Double.NaN;
        }
        double meanX = mean(x);
        double meanY = mean(y);
        double cross = 0;
        double squaresX = 0;
        double squaresY = 0;
        for (int i = 0; i < x.size(); i++) {
            double dx = x.get(i) - meanX;
            double dy = y.get(i) - meanY;
            cross += dx * dy;
            squaresX += dx * dx;
            squaresY += dy * dy;
        }
        if (squaresX == 0 || squaresY == 0) {
            return Double.NaN;
        }
        return cross / Math.sqrt(squaresX * squaresY);
    }
}
