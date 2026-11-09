package app.stageminute.analysis.score;

import java.util.Objects;

/**
 * The baseline for a focus check: the attempt of the same session that set the fix, with the fix it was given and the
 * facts measured on it. The caller loads both from the server's own {@code facts_record}, never from the client (03
 * F-033). When a fix is carried over to attempt 3, the baseline stays the attempt that first put it first, not the
 * attempt in between, so a level that goes 2, then 1, then 2 is not reported as an improvement (C-43).
 *
 * @param fix the fix the baseline report put first
 * @param facts the facts of that take
 */
public record PreviousTake(FixChoice fix, TakeFacts facts) {

    public PreviousTake {
        Objects.requireNonNull(fix, "fix");
        Objects.requireNonNull(facts, "facts");
    }
}
