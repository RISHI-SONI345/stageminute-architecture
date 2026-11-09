package app.stageminute.trust.stats;

/** A range of values a statistic could plausibly take (a 95 % bootstrap range). NaN ends mean "not computable". */
public record Interval(double low, double high) {

    public static final Interval NONE = new Interval(Double.NaN, Double.NaN);

    public boolean computable() {
        return !Double.isNaN(low) && !Double.isNaN(high);
    }
}
