package app.stageminute.trust.measures;

import app.stageminute.analysis.score.StepStatus;
import app.stageminute.trust.model.Observation;
import app.stageminute.trust.model.TakeCase;
import app.stageminute.trust.stats.Stats;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** One printed line, no gate: do longer takes get stronger step labels? The correlation of length and strong steps. */
public final class LengthBias {

    private static final int MIN_TAKES = 3;

    private LengthBias() {}

    /** Pearson correlation of take length and the number of strong steps, or NaN with fewer than 3 takes. */
    public static double of(List<TakeCase> cases) {
        List<Double> lengths = new ArrayList<>();
        List<Double> strong = new ArrayList<>();
        for (TakeCase take : cases) {
            Optional<Observation> run = take.firstScored();
            if (run.isEmpty()) {
                continue;
            }
            lengths.add((double) run.get().measuredDurationMs());
            strong.add((double) run.get().steps().stream()
                    .filter(step -> step == StepStatus.STRONG)
                    .count());
        }
        return lengths.size() < MIN_TAKES ? Double.NaN : Stats.pearson(lengths, strong);
    }
}
