package app.stageminute.analysis.score;

import java.util.Objects;

/**
 * The fix the report asks the person to work on (03 F-026 `fix_first`, F-032). Ids and flags only: no words.
 *
 * @param id the fix
 * @param target the step it is about (0 Point or Situation to 3 Closing or Result), or -1 when it is not about one
 *     step
 * @param carriedOver whether it is the fix from the previous attempt, kept because its focus check was not Done
 * @param nothingMajor whether this is the one polish of a strong take ("Nothing major to fix")
 */
public record FixChoice(FixId id, int target, boolean carriedOver, boolean nothingMajor) {

    public static final int NO_TARGET = -1;

    public FixChoice {
        Objects.requireNonNull(id, "id");
        if (target < NO_TARGET || target > 3) {
            throw new IllegalArgumentException("target is -1 or a step 0 to 3, was " + target);
        }
        if (id.aboutOneStep() && target == NO_TARGET) {
            throw new IllegalArgumentException(id.code() + " is about one step, so it needs a step 0 to 3");
        }
    }

    static FixChoice of(FixId id) {
        return new FixChoice(id, NO_TARGET, false, false);
    }

    static FixChoice ofStep(FixId id, int step) {
        return new FixChoice(id, step, false, false);
    }

    FixChoice carried() {
        return new FixChoice(id, target, true, nothingMajor);
    }
}
