package app.stageminute.analysis.score;

/**
 * Language points (03 F-030). Pure: no clock, no user, no Spring.
 *
 * <p>Each of the three criteria is worth a third of the part, and its level earns a percentage of that. The three
 * shares are added exactly and rounded once, half up: points = maxPoints x (sum of percentages) / 300. Whole
 * numbers only, so 24.5 is never lost to floating point.
 */
public final class LanguageScore {

    private static final int CRITERIA = 3;
    private static final int PERCENT = 100;

    private LanguageScore() {}

    public static int points(LanguageLevels levels, LanguageSettings settings) {
        int sumPct = settings.pctFor(levels.clarity())
                + settings.pctFor(levels.conciseness())
                + settings.pctFor(levels.accuracy());
        long denominator = (long) CRITERIA * PERCENT;
        return (int) (((long) settings.maxPoints() * sumPct + denominator / 2) / denominator);
    }
}
