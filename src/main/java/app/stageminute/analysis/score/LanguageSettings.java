package app.stageminute.analysis.score;

/**
 * Settings of the Language part (03 F-030). {@link #LAUNCH_DEFAULT} holds the approved launch values (D-55).
 *
 * @param maxPoints points the part can earn (35); each of the three criteria is worth a third of it
 * @param level4Pct share of a criterion's points that level 4 earns (100)
 * @param level3Pct level 3 (70)
 * @param level2Pct level 2 (40)
 * @param level1Pct level 1 (10)
 */
public record LanguageSettings(int maxPoints, int level4Pct, int level3Pct, int level2Pct, int level1Pct) {

    public static final LanguageSettings LAUNCH_DEFAULT = new LanguageSettings(35, 100, 70, 40, 10);

    public LanguageSettings {
        if (maxPoints <= 0) {
            throw new IllegalArgumentException("maxPoints must be positive");
        }
        if (!(level4Pct <= 100
                && level4Pct > level3Pct
                && level3Pct > level2Pct
                && level2Pct > level1Pct
                && level1Pct > 0)) {
            throw new IllegalArgumentException("level shares must fall: 100 >= L4 > L3 > L2 > L1 > 0");
        }
    }

    int pctFor(int level) {
        return switch (level) {
            case 4 -> level4Pct;
            case 3 -> level3Pct;
            case 2 -> level2Pct;
            default -> level1Pct;
        };
    }
}
