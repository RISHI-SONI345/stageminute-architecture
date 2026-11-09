package app.stageminute.trust.measures;

import app.stageminute.analysis.score.StepStatus;
import app.stageminute.trust.model.Observation;
import app.stageminute.trust.model.Reference;
import app.stageminute.trust.model.TakeCase;
import app.stageminute.trust.stats.Kappa;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Step-status agreement (11 section 1: at least 85 % with the annotators). Every step of every scored take is compared
 * with each annotator separately and the cells are pooled, so an annotator disagreement shows as lost agreement
 * instead of being averaged away. Cohen's kappa is printed next to it (the pass line stays on raw agreement).
 */
public final class StepAgreement {

    private StepAgreement() {}

    /**
     * The pipeline against the annotators.
     *
     * @param agreement share of cells with the same label, or NaN when there are none
     * @param kappa Cohen's kappa over the same cells, or NaN when there are none
     * @param cells how many step-by-annotator cells were compared
     */
    public record Result(double agreement, double kappa, int cells) {}

    public static Result pipelineVsAnnotators(List<TakeCase> cases) {
        List<String> pipeline = new ArrayList<>();
        List<String> annotators = new ArrayList<>();
        for (TakeCase take : cases) {
            Optional<Observation> run = take.firstScored();
            if (run.isEmpty()) {
                continue;
            }
            for (Reference reference : take.references()) {
                for (int step = 0;
                        step < reference.steps().size()
                                && step < run.get().steps().size();
                        step++) {
                    pipeline.add(label(run.get().steps().get(step)));
                    annotators.add(label(reference.steps().get(step)));
                }
            }
        }
        if (pipeline.isEmpty()) {
            return new Result(Double.NaN, Double.NaN, 0);
        }
        return new Result(Kappa.rawAgreement(pipeline, annotators), Kappa.cohen(pipeline, annotators), pipeline.size());
    }

    /** How often two annotators of the same take agree on a step; NaN when no take has two annotators. */
    public static double annotatorsVsEachOther(List<TakeCase> cases) {
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        for (TakeCase take : cases) {
            List<Reference> references = take.references();
            for (int a = 0; a < references.size(); a++) {
                for (int b = a + 1; b < references.size(); b++) {
                    int steps = Math.min(
                            references.get(a).steps().size(),
                            references.get(b).steps().size());
                    for (int step = 0; step < steps; step++) {
                        first.add(label(references.get(a).steps().get(step)));
                        second.add(label(references.get(b).steps().get(step)));
                    }
                }
            }
        }
        return first.isEmpty() ? Double.NaN : Kappa.rawAgreement(first, second);
    }

    static String label(StepStatus status) {
        return status.name();
    }
}
