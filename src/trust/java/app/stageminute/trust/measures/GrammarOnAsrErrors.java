package app.stageminute.trust.measures;

import app.stageminute.trust.model.Observation;
import app.stageminute.trust.model.Reference;
import app.stageminute.trust.model.Span;
import app.stageminute.trust.model.TakeCase;
import java.util.List;
import java.util.Optional;

/**
 * Grammar items raised on speech-recognition errors (11 section 1: at most 5 %). A grammar item is a fault when the
 * words it is about were not what the speaker said. Annotators mark the stretches where the transcript did not match
 * the speech; an item whose words overlap a stretch marked by either annotator counts (strict: one doubt is enough).
 * The share is taken over all grammar items of all takes that have annotators.
 */
public final class GrammarOnAsrErrors {

    private GrammarOnAsrErrors() {}

    /** The share from 0 to 1, or NaN when the pipeline raised no grammar item. */
    public static double of(List<TakeCase> cases) {
        int items = 0;
        int onErrors = 0;
        for (TakeCase take : cases) {
            Optional<Observation> run = take.firstScored();
            if (run.isEmpty() || take.references().isEmpty()) {
                continue;
            }
            for (Span item : run.get().grammarSpans()) {
                items++;
                if (overlapsAnError(item, take.references())) {
                    onErrors++;
                }
            }
        }
        return items == 0 ? Double.NaN : (double) onErrors / items;
    }

    private static boolean overlapsAnError(Span item, List<Reference> references) {
        return references.stream()
                .flatMap(reference -> reference.asrErrorSpans().stream())
                .anyMatch(item::overlaps);
    }
}
