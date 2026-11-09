package app.stageminute.analysis.score;

/**
 * The model's three Language labels, each level 1 (weakest) to 4 (strongest) (03 F-029). Labels, never points.
 *
 * @param clarity how clear the take was
 * @param conciseness how much was said for the words used
 * @param accuracy how correct the language was
 */
public record LanguageLevels(int clarity, int conciseness, int accuracy) {

    public LanguageLevels {
        requireLevel(clarity, "clarity");
        requireLevel(conciseness, "conciseness");
        requireLevel(accuracy, "accuracy");
    }

    private static void requireLevel(int level, String name) {
        if (level < 1 || level > 4) {
            throw new IllegalArgumentException(name + " is level 1 to 4, was " + level);
        }
    }
}
