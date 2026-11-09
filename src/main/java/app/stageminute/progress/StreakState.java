package app.stageminute.progress;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Which Progress card the person sees (02b F-053). The server chooses; the phone only maps it to a board. */
public enum StreakState {
    /** No session has counted yet: "Your first session starts your streak." (no "Day 1"). */
    NEW_USER,
    /** Something counted today: "You practiced today. See you tomorrow." */
    PRACTICED_TODAY,
    /** A streak is alive and today is open: "Practice today to make it 13." */
    PRACTICE_TODAY,
    /** The streak ended a short while ago: "Your 12-day streak ended. Best: 21 days." */
    STREAK_ENDED,
    /** The last counted day is older than the recent-takes window: "Welcome back." */
    RETURNING_AFTER_GAP;

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
