package app.stageminute.trust.stats;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agreement between two labellers, raw and after chance (Cohen's kappa). Raw agreement of 85 % can be a kappa near
 * 0.5 when most labels are the same word, so the harness prints both.
 */
public final class Kappa {

    private Kappa() {}

    /** The share of positions where both lists carry the same label. */
    public static double rawAgreement(List<String> a, List<String> b) {
        requireSameNonEmpty(a, b);
        int same = 0;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).equals(b.get(i))) {
                same++;
            }
        }
        return (double) same / a.size();
    }

    /**
     * Cohen's kappa: (observed - chance) / (1 - chance), where chance is the agreement two labellers would reach by
     * giving the same label shares independently. When chance is 1 (everyone used one label) and they agree, the
     * answer is 1.
     */
    public static double cohen(List<String> a, List<String> b) {
        requireSameNonEmpty(a, b);
        double observed = rawAgreement(a, b);
        Map<String, Integer> countA = new HashMap<>();
        Map<String, Integer> countB = new HashMap<>();
        for (int i = 0; i < a.size(); i++) {
            countA.merge(a.get(i), 1, Integer::sum);
            countB.merge(b.get(i), 1, Integer::sum);
        }
        double chance = 0;
        double n = a.size();
        for (Map.Entry<String, Integer> entry : countA.entrySet()) {
            chance += (entry.getValue() / n) * (countB.getOrDefault(entry.getKey(), 0) / n);
        }
        if (chance >= 1.0) {
            return observed >= 1.0 ? 1.0 : 0.0;
        }
        return (observed - chance) / (1.0 - chance);
    }

    private static void requireSameNonEmpty(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            throw new IllegalArgumentException(
                    "two label lists must be the same length: " + a.size() + " and " + b.size());
        }
        if (a.isEmpty()) {
            throw new IllegalArgumentException("no labels to compare");
        }
    }
}
