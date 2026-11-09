package app.stageminute.analysis.score;

import java.util.Objects;

/**
 * One marked place on the take map. A kind and two times: no words (root CLAUDE.md, privacy rules).
 *
 * @param kind what the place is
 * @param startMs when it starts, in milliseconds from the start of the take
 * @param endMs when it ends
 */
public record Moment(MomentKind kind, int startMs, int endMs) {

    public Moment {
        Objects.requireNonNull(kind, "kind");
        if (startMs < 0 || endMs < startMs) {
            throw new IllegalArgumentException("a moment needs 0 <= start <= end");
        }
    }

    /**
     * Whether the moment shares time with the span. Touching at one instant does not count: Deepgram often gives a word
     * an end time equal to the next word's start, so a place that ends where a sentence starts is not the same place.
     */
    boolean overlaps(int otherStartMs, int otherEndMs) {
        return startMs < otherEndMs && otherStartMs < endMs;
    }
}
