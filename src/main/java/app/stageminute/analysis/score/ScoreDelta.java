package app.stageminute.analysis.score;

import java.util.Locale;

/**
 * Which way the total moved since the previous attempt (03 F-026 {@code delta.direction}; 02b F-045). A change
 * smaller than the noise band is {@code same}, so the app says "About the same" and never "+3". Pure.
 */
public final class ScoreDelta {

    /** The direction on the wire: {@code up}, {@code down} or {@code same}. */
    public enum Direction {
        UP,
        DOWN,
        SAME;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private ScoreDelta() {}

    /**
     * The direction from the previous total to this one.
     *
     * @param sameWithinPoints a change of fewer points than this either way is the same (the 7-point noise band)
     */
    public static Direction direction(int previousTotal, int total, int sameWithinPoints) {
        if (sameWithinPoints < 0) {
            throw new IllegalArgumentException("sameWithinPoints cannot be negative, was " + sameWithinPoints);
        }
        int change = total - previousTotal;
        if (change == 0 || Math.abs(change) < sameWithinPoints) {
            return Direction.SAME;
        }
        return change > 0 ? Direction.UP : Direction.DOWN;
    }
}
