package app.stageminute.progress;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * How one dot of the week strip looks (02b F-053). The server decides, so the phone does no date logic. The phone adds
 * its own "syncing" dot for a take that is waiting to send.
 */
public enum DayState {
    /** A take of that day counts (delivered, or given back after our own failure). */
    PRACTICED,
    /** Today, nothing counted yet. */
    TODAY,
    /** A day that has not come yet. */
    UPCOMING,
    /** A day gone by with nothing that counts. */
    MISSED,
    /** A day we froze because we failed the person (outage or cost pause): the streak is safe (C-88). */
    FROZEN,
    /** A day before the person's first session: not a miss (C-88). */
    BEFORE_START;

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
