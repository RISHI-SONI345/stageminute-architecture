package app.stageminute.practice;

import java.util.Locale;

/**
 * The four quota words (03 F-051). {@code POST /sessions} and {@code GET /me} send the same words, so the app
 * shows the same board whichever one it read.
 */
public enum QuotaState {
    OK,
    QUOTA_ZERO,
    PRO_CAP,
    THROTTLED;

    /** The word on the wire, for example {@code quota_zero}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
