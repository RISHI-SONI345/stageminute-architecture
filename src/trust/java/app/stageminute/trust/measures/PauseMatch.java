package app.stageminute.trust.measures;

import app.stageminute.trust.model.Observation;
import app.stageminute.trust.model.PausePoint;
import app.stageminute.trust.model.TakeCase;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * How well pauses are found, for the accent-fairness gate: the F1 of the recorded pauses against the pauses any
 * annotator marked (precision and recall together, so a recogniser that invents pauses and one that misses them both
 * lose). Pauses match when they start within {@value FillerPrecision#TOLERANCE_MS} ms. Counted over every take.
 */
public final class PauseMatch {

    private PauseMatch() {}

    /** The F1 from 0 to 1, or NaN when neither the pipeline nor the annotators found a pause. */
    public static double f1(List<TakeCase> cases) {
        int found = 0;
        int foundRight = 0;
        int marked = 0;
        int markedFound = 0;
        for (TakeCase take : cases) {
            Optional<Observation> run = take.firstScored();
            if (run.isEmpty() || take.references().isEmpty()) {
                continue;
            }
            List<PausePoint> labelled = new ArrayList<>();
            take.references().forEach(reference -> labelled.addAll(reference.pauses()));
            List<PausePoint> recorded = run.get().pauses();
            found += recorded.size();
            marked += labelled.size();
            for (PausePoint pause : recorded) {
                if (labelled.stream().anyMatch(other -> near(pause, other))) {
                    foundRight++;
                }
            }
            for (PausePoint pause : labelled) {
                if (recorded.stream().anyMatch(other -> near(pause, other))) {
                    markedFound++;
                }
            }
        }
        if (found + marked == 0) {
            return Double.NaN;
        }
        double precision = found == 0 ? 0.0 : (double) foundRight / found;
        double recall = marked == 0 ? 0.0 : (double) markedFound / marked;
        return precision + recall == 0 ? 0.0 : 2 * precision * recall / (precision + recall);
    }

    private static boolean near(PausePoint a, PausePoint b) {
        return Math.abs(a.startMs() - b.startMs()) <= FillerPrecision.TOLERANCE_MS;
    }
}
