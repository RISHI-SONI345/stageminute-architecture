package app.stageminute.analysis.score;

import java.util.Objects;
import java.util.Optional;

/**
 * What the Fix Library decided for a take: the fix to put first, and the focus check of the previous fix when there
 * was a previous attempt to check (03 F-026 `fix_first` and `focus_check`).
 *
 * @param fix the fix for the report's "fix first"
 * @param focusCheck the check of the previous attempt's fix; empty on the first attempt, after a style change, or when
 *     the earlier take failed or was not scored (EC-051)
 */
public record FixSelection(FixChoice fix, Optional<FocusCheckResult> focusCheck) {

    public FixSelection {
        Objects.requireNonNull(fix, "fix");
        Objects.requireNonNull(focusCheck, "focusCheck");
    }
}
