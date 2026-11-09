package app.stageminute.analysis.score;

/**
 * Turns whole-number points into band words (03 F-030, D-13). Pure: no clock, no user, no tier,
 * no Spring (ArchitectureTest enforces it).
 *
 * <p>Rounding rule (F-030): each part is rounded to a whole number before it reaches this class,
 * the total is the sum of the rounded parts, and bands use those rounded values. This class only
 * accepts whole numbers so a fractional value cannot slip past the rule.
 */
public final class ScoreBands {

    private ScoreBands() {}

    /** Band for the 0..100 total. */
    public static Band overall(int total, BandCutoffs cutoffs) {
        if (total < 0 || total > 100) {
            throw new IllegalArgumentException("total must be 0..100, was " + total);
        }
        if (total >= cutoffs.overallExcellent()) {
            return Band.EXCELLENT;
        }
        if (total >= cutoffs.overallStrong()) {
            return Band.STRONG;
        }
        if (total >= cutoffs.overallGood()) {
            return Band.GOOD;
        }
        return Band.OPPORTUNITY;
    }

    /**
     * Band for one part (Structure 45, Language 35, Delivery 20) by its share of the part's points.
     * A part has no Excellent band. Compared in integers ({@code points * 100 >= pct * max}) so
     * exactly 70% or 85% is never lost to floating point.
     */
    public static Band part(int points, int max, BandCutoffs cutoffs) {
        if (max <= 0) {
            throw new IllegalArgumentException("max must be positive, was " + max);
        }
        if (points < 0 || points > max) {
            throw new IllegalArgumentException("points must be 0.." + max + ", was " + points);
        }
        long scaled = (long) points * 100;
        if (scaled >= (long) cutoffs.partStrongPct() * max) {
            return Band.STRONG;
        }
        if (scaled >= (long) cutoffs.partGoodPct() * max) {
            return Band.GOOD;
        }
        return Band.OPPORTUNITY;
    }
}
