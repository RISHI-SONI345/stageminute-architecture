package app.stageminute.topics;

import java.util.Locale;
import java.util.Optional;

/** How a take is built: PREP (point, reason, example, point) or STAR (situation, task, action, result). */
public enum Style {
    PREP,
    STAR;

    /** The word used in files and in the API: {@code prep} or {@code star}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Style> fromWire(String text) {
        for (Style style : values()) {
            if (style.wire().equals(text)) {
                return Optional.of(style);
            }
        }
        return Optional.empty();
    }
}
