package app.stageminute.analysis.score;

/**
 * One word Deepgram heard, with its times and confidence (03 F-027). The text is speech, so it never prints:
 * {@link #toString()} hides it (root CLAUDE.md, privacy rules: logs carry no transcript).
 *
 * @param text the word as Deepgram wrote it
 * @param startMs when the word starts, in milliseconds from the start of the take
 * @param endMs when the word ends
 * @param confidence Deepgram's confidence, 0 to 1
 */
public record TimedWord(String text, int startMs, int endMs, double confidence) {

    public TimedWord {
        if (text == null) {
            throw new IllegalArgumentException("text is required");
        }
        if (startMs < 0 || endMs < startMs) {
            throw new IllegalArgumentException("a word needs 0 <= start <= end");
        }
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence is 0..1, was " + confidence);
        }
    }

    @Override
    public String toString() {
        return "TimedWord[redacted]";
    }
}
