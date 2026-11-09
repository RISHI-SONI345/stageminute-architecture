package app.stageminute.trust.measures;

import app.stageminute.trust.model.Observation;
import app.stageminute.trust.model.Reference;
import app.stageminute.trust.model.TakeCase;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * How well the pace is measured, for the accent-fairness gate. Pace is words over time and the time is measured, so the
 * pace is right when the word count is right. Each annotator gives how many words were said; a take scores one minus
 * the share it is off by (never below 0); the measure is the mean over takes. The annotators' word counts are counts
 * only, never the words.
 */
public final class PaceAccuracy {

    private PaceAccuracy() {}

    /** The accuracy from 0 to 1, or NaN when no take has an annotator word count. */
    public static double of(List<TakeCase> cases) {
        double sum = 0;
        int takes = 0;
        for (TakeCase take : cases) {
            Optional<Observation> run = take.firstScored();
            OptionalDouble said = take.references().stream()
                    .map(Reference::wordCount)
                    .filter(count -> count.isPresent() && count.getAsInt() > 0)
                    .mapToInt(count -> count.getAsInt())
                    .average();
            if (run.isEmpty() || said.isEmpty()) {
                continue;
            }
            double off = Math.abs(run.get().words() - said.getAsDouble()) / said.getAsDouble();
            sum += Math.max(0.0, 1.0 - off);
            takes++;
        }
        return takes == 0 ? Double.NaN : sum / takes;
    }
}
