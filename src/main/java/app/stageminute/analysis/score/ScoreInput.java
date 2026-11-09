package app.stageminute.analysis.score;

import java.util.List;
import java.util.Objects;

/**
 * Everything the score is built from: the model's labels and the code's counts. No user, no plan, no clock, no
 * words (03 F-030: "never reads the user's tier").
 *
 * @param style PREP or STAR
 * @param steps the four step labels in the style's order
 * @param relevanceLevel relevance label, level 1 (off topic) to 4
 * @param language the three Language labels
 * @param delivery counts and pace windows measured by code
 * @param paceBand the pace band for this take (launch default or the person's own)
 */
public record ScoreInput(
        Style style,
        List<StepStatus> steps,
        int relevanceLevel,
        LanguageLevels language,
        DeliveryFacts delivery,
        PaceBand paceBand) {

    public ScoreInput {
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(paceBand, "paceBand");
        steps = List.copyOf(steps);
    }
}
