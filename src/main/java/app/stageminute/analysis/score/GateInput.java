package app.stageminute.analysis.score;

/**
 * What the no-score gate needs to know about a take. Numbers and one yes/no only: no words, no audio.
 *
 * @param durationMs length of the take in milliseconds
 * @param wordCount words Deepgram heard
 * @param englishHeard whether English speech was detected
 * @param lowConfidenceWords words Deepgram heard with low confidence (F-036)
 */
public record GateInput(int durationMs, int wordCount, boolean englishHeard, int lowConfidenceWords) {

    public GateInput {
        if (durationMs < 0 || wordCount < 0 || lowConfidenceWords < 0) {
            throw new IllegalArgumentException("a take cannot have a negative length or word count");
        }
        if (lowConfidenceWords > wordCount) {
            throw new IllegalArgumentException("low-confidence words cannot outnumber the words heard");
        }
    }
}
