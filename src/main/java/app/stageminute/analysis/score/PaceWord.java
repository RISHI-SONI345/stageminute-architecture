package app.stageminute.analysis.score;

import java.util.Locale;

/**
 * The one word Delivery shows under the pace number (step3/CONFLICTS.md C-65; D-88 in step3/ROWS-TO-APPLY-1.8.md).
 * Built by {@link PaceWords}; it gives no points and is never part of the total. The app maps each wire word to a copy
 * key.
 */
public enum PaceWord {
    STEADY,
    A_BIT_FAST,
    A_BIT_SLOW,
    UNEVEN;

    /** The word on the wire, for example {@code a_bit_fast}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
