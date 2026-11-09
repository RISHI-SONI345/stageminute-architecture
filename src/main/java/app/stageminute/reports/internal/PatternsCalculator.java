package app.stageminute.reports.internal;

import app.stageminute.reports.Patterns;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The pure rule of the patterns cards (03 F-056): count, per step of the style, how many of the last first takes were
 * strong and how many were weak or missing. No clock, no database, no user.
 */
public final class PatternsCalculator {

    private static final List<String> PREP = List.of("point", "reason", "example", "closing");
    private static final List<String> STAR = List.of("situation", "task", "action", "result");

    private PatternsCalculator() {}

    /** The step codes of a style in speaking order. */
    public static List<String> stepCodes(String style) {
        return "star".equals(style) ? STAR : PREP;
    }

    /** True for a style code the patterns know. */
    public static boolean knownStyle(@Nullable String style) {
        return "prep".equals(style) || "star".equals(style);
    }

    /**
     * Works out the cards.
     *
     * @param style {@code prep} or {@code star}
     * @param takes the step labels ({@code strong}, {@code weak}, {@code missing}) of each first take, newest first
     * @param needed takes needed before the cards show
     * @param strongMin takes a step must be strong in to be the strength
     * @param opportunityMin takes a step must be weak or missing in to be the opportunity
     * @param fixed the second-attempt part, built by the caller
     * @return the cards; with fewer than {@code needed} takes only the counts
     */
    public static Patterns compute(
            String style,
            List<List<String>> takes,
            int needed,
            int strongMin,
            int opportunityMin,
            Patterns.FixedOnSecond fixed) {
        int counted = Math.min(takes.size(), needed);
        if (counted < needed) {
            return new Patterns(style, counted, needed, null, null, false, fixed);
        }
        List<String> codes = stepCodes(style);
        Patterns.Strength strength = null;
        Patterns.Opportunity opportunity = null;
        for (int step = 0; step < codes.size(); step++) {
            int strong = 0;
            for (List<String> labels : takes.subList(0, counted)) {
                if (step < labels.size() && "strong".equals(labels.get(step))) {
                    strong++;
                }
            }
            int notStrong = counted - strong;
            if (strong >= strongMin && (strength == null || strong > strength.strongIn())) {
                strength = new Patterns.Strength(codes.get(step), strong);
            }
            if (notStrong >= opportunityMin && (opportunity == null || notStrong > opportunity.weakOrMissingIn())) {
                opportunity = new Patterns.Opportunity(codes.get(step), notStrong);
            }
        }
        return new Patterns(style, counted, needed, strength, opportunity, opportunity == null, fixed);
    }
}
