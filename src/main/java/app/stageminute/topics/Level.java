package app.stageminute.topics;

import java.util.Locale;
import java.util.Optional;

/** The speaker's English level, from the easiest. The word in files and in the API is {@link #wire()}. */
public enum Level {
    STARTER,
    ELEMENTARY,
    INTERMEDIATE,
    UPPER_INTERMEDIATE;

    /** For example {@code upper_intermediate}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Level> fromWire(String text) {
        for (Level level : values()) {
            if (level.wire().equals(text)) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }
}
