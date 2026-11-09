package app.stageminute.analysis.score;

import java.util.List;

/**
 * What Metrics v2 reads: the words in order, the sentences over them, and the length of the take. This is speech: it
 * lives in memory for one request and is never stored or logged (F-026, F-091). Its {@code toString} shows no text
 * because each {@link TimedWord} hides its own.
 *
 * @param words the words in the order spoken
 * @param sentences sentences over the words, in order
 * @param durationMs length of the take in milliseconds
 */
public record Transcript(List<TimedWord> words, List<SentenceSpan> sentences, int durationMs) {

    public Transcript {
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs cannot be negative");
        }
        words = List.copyOf(words);
        sentences = List.copyOf(sentences);
        for (SentenceSpan sentence : sentences) {
            if (sentence.lastWord() >= words.size()) {
                throw new IllegalArgumentException("a sentence points past the last word");
            }
        }
    }
}
