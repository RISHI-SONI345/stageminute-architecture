package app.stageminute.analysis.vendor;

import app.stageminute.analysis.score.Transcript;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What speech to text heard: the words and sentences, and whether it was English (C-28: the caller passes "English
 * heard" as a yes or no). Speech: kept in memory for one request only; its {@code toString} shows no word because
 * each word hides its own text.
 *
 * @param transcript the timed words and sentences; its length is the server-measured length
 * @param englishHeard whether English speech was detected
 * @param answeredModel the model name the vendor says it used (a real client only), or null
 */
public record SpeechResult(
        Transcript transcript,
        boolean englishHeard,
        @Nullable String answeredModel) {

    /** A result from a client that does not say which model it used (the fake). */
    public SpeechResult(Transcript transcript, boolean englishHeard) {
        this(transcript, englishHeard, null);
    }

    public SpeechResult {
        Objects.requireNonNull(transcript, "transcript");
    }
}
