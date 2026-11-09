package app.stageminute.analysis.score;

/**
 * A sentence as the positions of its first and last word in the transcript's word list (both included). Positions
 * instead of text, so the sentence carries no speech.
 */
public record SentenceSpan(int firstWord, int lastWord) {

    public SentenceSpan {
        if (firstWord < 0 || lastWord < firstWord) {
            throw new IllegalArgumentException("a sentence needs 0 <= first <= last");
        }
    }

    int wordCount() {
        return lastWord - firstWord + 1;
    }
}
