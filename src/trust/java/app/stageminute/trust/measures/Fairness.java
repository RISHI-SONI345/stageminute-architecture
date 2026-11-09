package app.stageminute.trust.measures;

import app.stageminute.trust.model.TakeCase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;

/**
 * Fairness across accent groups (11 section 1: pace, pause and filler measures within 3 points; promise 4, "Delivery
 * is measured from timing, not your accent or voice"). A measure from 0 to 1 is taken for each accent group alone, and
 * the gate is the gap in points between the best and the worst group.
 */
public final class Fairness {

    private static final double POINTS = 100.0;

    private Fairness() {}

    /** The measure for each accent group that has a number, in name order. */
    public static Map<String, Double> byGroup(List<TakeCase> cases, ToDoubleFunction<List<TakeCase>> measure) {
        Map<String, List<TakeCase>> groups = new TreeMap<>();
        for (TakeCase take : cases) {
            groups.computeIfAbsent(take.accentGroup(), key -> new ArrayList<>()).add(take);
        }
        Map<String, Double> values = new TreeMap<>();
        groups.forEach((group, members) -> {
            double value = measure.applyAsDouble(members);
            if (!Double.isNaN(value)) {
                values.put(group, value);
            }
        });
        return values;
    }

    /** The gap in points between the best and worst group, or NaN when fewer than two groups have a number. */
    public static double spreadPoints(List<TakeCase> cases, ToDoubleFunction<List<TakeCase>> measure) {
        Map<String, Double> values = byGroup(cases, measure);
        if (values.size() < 2) {
            return Double.NaN;
        }
        double high =
                values.values().stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        double low =
                values.values().stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        return (high - low) * POINTS;
    }
}
