package app.stageminute.trust.measures;

import app.stageminute.trust.model.Observation;
import app.stageminute.trust.model.TakeCase;
import app.stageminute.trust.stats.Kappa;
import java.util.ArrayList;
import java.util.List;

/**
 * Run-to-run agreement of the step labels (11 section 1: at least 90 % over 5 runs): the same recording sent through
 * the one call again and again. Every pair of runs of a clip is compared step by step and the cells are pooled.
 * Cohen's kappa is printed next to it, over both orders of every pair so the two sides have the same label shares.
 */
public final class RunToRun {

    private RunToRun() {}

    /**
     * The result over the clips.
     *
     * @param agreement share of step cells that are the same in both runs of a pair, or NaN
     * @param kappa Cohen's kappa over the same pairs, or NaN
     * @param pairs how many pairs of runs were compared
     */
    public record Result(double agreement, double kappa, int pairs) {}

    public static Result of(List<TakeCase> clips) {
        List<String> left = new ArrayList<>();
        List<String> right = new ArrayList<>();
        int same = 0;
        int cells = 0;
        int pairs = 0;
        for (TakeCase clip : clips) {
            List<Observation> runs = clip.scoredRuns();
            for (int i = 0; i < runs.size(); i++) {
                for (int j = i + 1; j < runs.size(); j++) {
                    pairs++;
                    int steps = Math.min(
                            runs.get(i).steps().size(), runs.get(j).steps().size());
                    for (int step = 0; step < steps; step++) {
                        String a = StepAgreement.label(runs.get(i).steps().get(step));
                        String b = StepAgreement.label(runs.get(j).steps().get(step));
                        cells++;
                        if (a.equals(b)) {
                            same++;
                        }
                        left.add(a);
                        right.add(b);
                        left.add(b);
                        right.add(a);
                    }
                }
            }
        }
        if (cells == 0) {
            return new Result(Double.NaN, Double.NaN, 0);
        }
        return new Result((double) same / cells, Kappa.cohen(left, right), pairs);
    }
}
