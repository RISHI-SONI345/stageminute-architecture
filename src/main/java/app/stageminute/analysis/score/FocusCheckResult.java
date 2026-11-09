package app.stageminute.analysis.score;

import java.util.Objects;
import java.util.Optional;

/**
 * The report's `focus_check` (03 F-026, F-033).
 *
 * @param fixId the fix that was being checked
 * @param result Done, Better or Not yet
 * @param evidence the measure before and after
 * @param previousStatus the step's status on the previous attempt, for the fixes that are about one step; empty for
 *     the others
 */
public record FocusCheckResult(
        FixId fixId, FocusResult result, FocusEvidence evidence, Optional<StepStatus> previousStatus) {

    public FocusCheckResult {
        Objects.requireNonNull(fixId, "fixId");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(previousStatus, "previousStatus");
    }
}
