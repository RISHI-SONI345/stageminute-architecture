package app.stageminute.trust.model;

/** A stretch of a take in milliseconds from its start; both ends count as inside. */
public record Span(int startMs, int endMs) {

    public Span {
        if (endMs < startMs) {
            throw new IllegalArgumentException("a span ends after it starts: " + startMs + ".." + endMs);
        }
    }

    public boolean overlaps(Span other) {
        return startMs <= other.endMs && other.startMs <= endMs;
    }
}
