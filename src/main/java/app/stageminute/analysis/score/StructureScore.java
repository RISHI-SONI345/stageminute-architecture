package app.stageminute.analysis.score;

import java.util.List;

/**
 * Structure points (03 F-030). Pure: no clock, no user, no Spring.
 *
 * <p>Each step earns its weight times a factor (strong, weak or missing). The sum is rounded once, half up, to a
 * whole number. Weights are in hundredths of a point and factors in percent, so the sum is exact whole numbers
 * (ten-thousandths of a point) and 22.5 is never lost to floating point.
 */
public final class StructureScore {

    private static final int STEPS = 4;
    private static final int TEN_THOUSANDTHS_PER_POINT = 10_000;

    private StructureScore() {}

    public static int points(Style style, List<StepStatus> steps, int relevanceLevel, StructureSettings settings) {
        if (steps.size() != STEPS) {
            throw new IllegalArgumentException("a take has " + STEPS + " steps, was " + steps.size());
        }
        if (relevanceLevel < 1 || relevanceLevel > 4) {
            throw new IllegalArgumentException("relevance is level 1 to 4, was " + relevanceLevel);
        }
        List<Integer> weights = settings.weightsFor(style).asList();
        long sum = 0;
        for (int i = 0; i < STEPS; i++) {
            sum += (long) weights.get(i) * settings.factorPct(steps.get(i));
        }
        int rounded = (int) ((sum + TEN_THOUSANDTHS_PER_POINT / 2) / TEN_THOUSANDTHS_PER_POINT);
        return relevanceLevel == 1 ? Math.min(rounded, settings.relevanceOneCap()) : rounded;
    }
}
