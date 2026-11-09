package app.stageminute.analysis.score;

import java.util.List;

/**
 * Builds the score from labels and counts (03 F-030): Structure 45 + Language 35 + Delivery 20. Code builds every
 * number; the model only supplies labels. Each part is rounded to a whole number first, the total is the sum of the
 * rounded parts, and every band uses the rounded values. Pure: no clock, no user, no tier, no Spring.
 */
public final class ScoreBuilder {

    private ScoreBuilder() {}

    public static ScoreResult build(ScoreInput input, ScoreSettings settings) {
        int structure =
                StructureScore.points(input.style(), input.steps(), input.relevanceLevel(), settings.structure());
        int language = LanguageScore.points(input.language(), settings.language());
        int delivery = DeliveryScore.points(input.delivery(), input.paceBand(), settings.delivery());

        List<PartScore> parts = List.of(
                part("structure", structure, settings.structureMax(), settings),
                part("language", language, settings.languageMax(), settings),
                part("delivery", delivery, settings.deliveryMax(), settings));
        int total = structure + language + delivery;
        return new ScoreResult(total, ScoreBands.overall(total, settings.cutoffs()), parts, settings.formulaVersion());
    }

    private static PartScore part(String id, int points, int max, ScoreSettings settings) {
        return new PartScore(id, points, max, ScoreBands.part(points, max, settings.cutoffs()));
    }
}
