package app.stageminute.analysis.score;

/**
 * Settings of the Structure part (03 F-030). {@link #LAUNCH_DEFAULT} holds the approved launch values (D-55).
 *
 * @param prep PREP weights: Point 13.5, Reason 9, Example 11.25, Closing 11.25
 * @param star STAR weights: Situation 9, Task 6.75, Action 15.75, Result 13.5
 * @param strongFactorPct share of a step's weight a strong step earns (100)
 * @param weakFactorPct share of a step's weight a weak step earns (50)
 * @param relevanceOneCap most points Structure can earn when relevance is level 1 (22)
 */
public record StructureSettings(
        StepWeights prep, StepWeights star, int strongFactorPct, int weakFactorPct, int relevanceOneCap) {

    public static final StructureSettings LAUNCH_DEFAULT = new StructureSettings(
            new StepWeights(1350, 900, 1125, 1125), new StepWeights(900, 675, 1575, 1350), 100, 50, 22);

    public StructureSettings {
        if (!(strongFactorPct <= 100 && weakFactorPct > 0 && weakFactorPct < strongFactorPct)) {
            throw new IllegalArgumentException("factors must fall: 100 >= strong > weak > 0");
        }
        if (relevanceOneCap <= 0) {
            throw new IllegalArgumentException("relevanceOneCap must be positive");
        }
    }

    StepWeights weightsFor(Style style) {
        return style == Style.PREP ? prep : star;
    }

    int factorPct(StepStatus status) {
        return switch (status) {
            case STRONG -> strongFactorPct;
            case WEAK -> weakFactorPct;
            case MISSING -> 0;
        };
    }
}
