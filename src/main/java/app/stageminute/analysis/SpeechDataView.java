package app.stageminute.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code speech_data} (03 F-026; 06-API section 6): the transcript, the timed words and the sentences of one take. It
 * goes to the phone once, in the {@code POST /v1/analyze} answer, and is kept by the server only in memory for the
 * 10-minute retry (F-091): never in the database, a log line, an error body or Sentry (rule 4). Every {@code toString}
 * here prints {@code [redacted]}, so it cannot reach a log by accident.
 *
 * <p>The model's rewording of the answer travels here too, not in the report (C-71): it is the person's content in
 * other words, so it stays on the phone with the transcript.
 *
 * @param transcript the words joined by spaces
 * @param words each word with its times and confidence
 * @param sentences each sentence by id with its times
 * @param rewrite "One way to say it" (the model, at most 60 words), or null
 * @param languageTries language item id (as in {@code findings.language_items} and {@code swaps}) to the model's
 *     suggested wording; empty for a take with no score
 */
@Schema(name = "SpeechData", description = "The take's words: returned once, never stored on the server")
public record SpeechDataView(
        String transcript,
        List<Word> words,
        List<Sentence> sentences,

        @Nullable @Schema(nullable = true, description = "One way to say it: the model, at most 60 words (C-71)")
        String rewrite,

        @Schema(description = "Language item id to the model's suggested wording (C-71)")
        @JsonProperty("language_tries")
        Map<String, String> languageTries) {

    /** One word as speech to text heard it. */
    @Schema(name = "SpeechWord")
    public record Word(
            String w,
            @JsonProperty("start_ms") int startMs,
            @JsonProperty("end_ms") int endMs,
            double conf) {

        @Override
        public String toString() {
            return "[redacted]";
        }
    }

    /** One sentence: its id (the number quotes and steps point at) and its times. */
    @Schema(name = "SpeechSentence")
    public record Sentence(
            int id,
            @JsonProperty("start_ms") int startMs,
            @JsonProperty("end_ms") int endMs) {}

    public SpeechDataView {
        words = List.copyOf(words);
        sentences = List.copyOf(sentences);
        languageTries = Map.copyOf(languageTries);
    }

    @Override
    public String toString() {
        return "[redacted]";
    }
}
