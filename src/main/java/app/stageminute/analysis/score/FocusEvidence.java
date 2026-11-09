package app.stageminute.analysis.score;

import java.util.Objects;

/**
 * The two numbers behind a focus result: the measure on the previous attempt and on this one. Numbers only (the shape
 * of `evidence` is not fixed by the requirements, see step3/CONFLICTS.md C-38).
 *
 * @param measure what was measured
 * @param before the value on the previous attempt
 * @param after the value on this attempt
 */
public record FocusEvidence(FocusMeasure measure, int before, int after) {

    public FocusEvidence {
        Objects.requireNonNull(measure, "measure");
    }
}
