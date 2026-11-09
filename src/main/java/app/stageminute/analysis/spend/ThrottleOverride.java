package app.stageminute.analysis.spend;

import java.util.List;

/**
 * The manual override of the AI-cost throttle (03 F-087), read from the setting {@code ai_throttle_override} (the admin
 * console that sets it is #27). Fails closed: one known code is obeyed; anything else (two codes, an unknown code, an
 * empty list) is {@link #FORCE_OPEN}. A row that is missing or not a list of codes gives the launch default, {@link
 * #AUTO}, as every setting does (AD-65).
 */
public enum ThrottleOverride {
    /** The levels follow the budget. */
    AUTO,
    /** Paused as if over budget: free users paused, the Pro ceiling lowered. */
    FORCE_OPEN,
    /** The share levels are ignored (the call stop still holds). */
    FORCE_CLOSED;

    /** The override for a stored list of codes. */
    public static ThrottleOverride of(List<String> codes) {
        if (codes.size() != 1) {
            return FORCE_OPEN;
        }
        return switch (codes.get(0)) {
            case "auto" -> AUTO;
            case "force_closed" -> FORCE_CLOSED;
            default -> FORCE_OPEN; // "force_open" and every code nobody knows
        };
    }
}
