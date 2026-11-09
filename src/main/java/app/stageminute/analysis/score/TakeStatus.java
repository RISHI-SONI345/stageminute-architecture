package app.stageminute.analysis.score;

/** What the no-score gate decided about a take (03 F-026 `status`, F-037). */
public enum TakeStatus {
    OK,
    SHORT_TAKE,
    LOW_AUDIO,
    NOT_ENGLISH,
    NO_SPEECH;

    /** Only an OK take or a low-audio take gets a score; the others return no score. */
    public boolean scored() {
        return this == OK || this == LOW_AUDIO;
    }
}
