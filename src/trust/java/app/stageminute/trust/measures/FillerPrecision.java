package app.stageminute.trust.measures;

import app.stageminute.trust.model.Observation;
import app.stageminute.trust.model.Reference;
import app.stageminute.trust.model.TakeCase;
import java.util.List;
import java.util.Optional;

/**
 * Filler precision (11 section 1: at least 0.90): of the fillers the pipeline confirmed, the share an annotator also
 * marked. A filler is matched by time, within {@value #TOLERANCE_MS} ms, because the annotator hears the word and the
 * recogniser times it. The rule says how many annotators must agree: {@link Rule#BOTH} (the default, strict: a filler
 * the annotators disagree on counts against us) or {@link Rule#EITHER}. Counted over every take together.
 */
public final class FillerPrecision {

    public static final int TOLERANCE_MS = 500;

    /** Which annotators must have marked the filler for it to count as right. */
    public enum Rule {
        BOTH,
        EITHER
    }

    private FillerPrecision() {}

    /** The precision from 0 to 1, or NaN when the pipeline confirmed no filler in a take with annotators. */
    public static double of(List<TakeCase> cases, Rule rule) {
        int confirmed = 0;
        int right = 0;
        for (TakeCase take : cases) {
            Optional<Observation> run = take.firstScored();
            if (run.isEmpty() || take.references().isEmpty()) {
                continue;
            }
            for (int start : run.get().fillerStartsMs()) {
                confirmed++;
                if (isRight(start, take.references(), rule)) {
                    right++;
                }
            }
        }
        return confirmed == 0 ? Double.NaN : (double) right / confirmed;
    }

    private static boolean isRight(int start, List<Reference> references, Rule rule) {
        return switch (rule) {
            case BOTH -> references.stream().allMatch(reference -> marked(start, reference));
            case EITHER -> references.stream().anyMatch(reference -> marked(start, reference));
        };
    }

    private static boolean marked(int start, Reference reference) {
        return reference.fillerStartsMs().stream().anyMatch(other -> Math.abs(other - start) <= TOLERANCE_MS);
    }
}
