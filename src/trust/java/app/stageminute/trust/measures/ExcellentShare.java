package app.stageminute.trust.measures;

import app.stageminute.analysis.score.Band;
import app.stageminute.analysis.score.BandCutoffs;
import app.stageminute.analysis.score.ScoreBands;
import app.stageminute.trust.model.Observation;
import app.stageminute.trust.model.TakeCase;
import java.util.List;
import java.util.Optional;

/**
 * The share of scored takes that read Excellent (11 section 1: "stays rare"; the target is set on the labelled set, so
 * the harness prints the number and no pass or fail). The cut-off is the score code's own band.
 */
public final class ExcellentShare {

    private ExcellentShare() {}

    /** The share from 0 to 1, or NaN when no take was scored. */
    public static double of(List<TakeCase> cases) {
        int scored = 0;
        int excellent = 0;
        for (TakeCase take : cases) {
            Optional<Observation> run = take.firstScored();
            if (run.isEmpty()) {
                continue;
            }
            scored++;
            if (ScoreBands.overall(run.get().total(), BandCutoffs.LAUNCH_DEFAULT) == Band.EXCELLENT) {
                excellent++;
            }
        }
        return scored == 0 ? Double.NaN : (double) excellent / scored;
    }
}
