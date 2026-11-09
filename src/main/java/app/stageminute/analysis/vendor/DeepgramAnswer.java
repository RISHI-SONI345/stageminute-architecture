package app.stageminute.analysis.vendor;

import app.stageminute.analysis.score.SentenceSpan;
import app.stageminute.analysis.score.TimedWord;
import app.stageminute.analysis.score.Transcript;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads Deepgram's answer into a {@link SpeechResult} (03 F-027): words with times and confidence, sentences as word
 * positions, and whether English was heard. The server-measured length stays the take's length (C-69). Any answer that
 * is not shaped as expected is {@link VendorUnavailable} with a fixed code, and no text of it is kept in the error.
 */
final class DeepgramAnswer {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final double MS_PER_SECOND = 1000.0;
    private static final int MAX_WORDS = 20_000;

    /**
     * What an answer holds.
     *
     * @param speech the words and whether English was heard
     * @param billedMs the audio time Deepgram says it processed, when it says
     * @param answeredModel the model name Deepgram says it used, when it says
     */
    record Parsed(
            SpeechResult speech,
            OptionalLong billedMs,
            @Nullable String answeredModel) {}

    private DeepgramAnswer() {}

    /** Parses the answer body, or throws {@code stt_bad_answer}. */
    static Parsed parse(byte[] body, int durationMs) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode channel = root.path("results").path("channels").path(0);
            JsonNode alternative = channel.path("alternatives").path(0);
            JsonNode wordNodes = alternative.path("words");
            if (!wordNodes.isArray() || wordNodes.size() > MAX_WORDS) {
                throw badAnswer();
            }
            List<TimedWord> words = new ArrayList<>();
            for (JsonNode node : wordNodes) {
                words.add(word(node));
            }
            List<SentenceSpan> sentences = sentences(alternative.path("paragraphs"), words);
            Transcript transcript = new Transcript(words, sentences, durationMs);
            String model = answeredModel(root.path("metadata"));
            return new Parsed(
                    new SpeechResult(transcript, englishHeard(channel), model), billedMs(root.path("metadata")), model);
        } catch (JacksonException | IllegalArgumentException e) {
            throw badAnswer();
        }
    }

    private static VendorUnavailable badAnswer() {
        return new VendorUnavailable("stt_bad_answer");
    }

    private static TimedWord word(JsonNode node) {
        JsonNode text = node.hasNonNull("punctuated_word") ? node.get("punctuated_word") : node.get("word");
        JsonNode start = node.get("start");
        JsonNode end = node.get("end");
        JsonNode confidence = node.get("confidence");
        if (text == null
                || !text.isString()
                || start == null
                || !start.isNumber()
                || end == null
                || !end.isNumber()
                || confidence == null
                || !confidence.isNumber()) {
            throw badAnswer();
        }
        return new TimedWord(text.asString(), millis(start.asDouble()), millis(end.asDouble()), confidence.asDouble());
    }

    private static int millis(double seconds) {
        return (int) Math.round(seconds * MS_PER_SECOND);
    }

    /**
     * Sentences as word positions. A sentence takes every word that starts before its end time; words left over join
     * the last sentence, and a take with words but no sentences is one sentence.
     */
    private static List<SentenceSpan> sentences(JsonNode paragraphs, List<TimedWord> words) {
        if (words.isEmpty()) {
            return List.of();
        }
        List<SentenceSpan> spans = new ArrayList<>();
        int next = 0;
        for (JsonNode paragraph : paragraphs.path("paragraphs")) {
            for (JsonNode sentence : paragraph.path("sentences")) {
                JsonNode end = sentence.get("end");
                if (end == null || !end.isNumber() || next >= words.size()) {
                    continue;
                }
                int endMs = millis(end.asDouble());
                int last = next;
                while (last + 1 < words.size() && words.get(last + 1).startMs() < endMs) {
                    last++;
                }
                spans.add(new SentenceSpan(next, last));
                next = last + 1;
            }
        }
        if (spans.isEmpty()) {
            return List.of(new SentenceSpan(0, words.size() - 1));
        }
        if (next < words.size()) {
            SentenceSpan lastSpan = spans.remove(spans.size() - 1);
            spans.add(new SentenceSpan(lastSpan.firstWord(), words.size() - 1));
        }
        return spans;
    }

    /** No language signal is read as English (a take is not refused for a missing field); a non-English code is not. */
    private static boolean englishHeard(JsonNode channel) {
        JsonNode language = channel.get("detected_language");
        if (language == null || !language.isString() || language.asString().isBlank()) {
            return true;
        }
        return language.asString().toLowerCase(Locale.ROOT).startsWith("en");
    }

    private static OptionalLong billedMs(JsonNode metadata) {
        JsonNode duration = metadata.get("duration");
        if (duration == null || !duration.isNumber() || duration.asDouble() < 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Math.round(duration.asDouble() * MS_PER_SECOND));
    }

    private static @Nullable String answeredModel(JsonNode metadata) {
        for (JsonNode info : metadata.path("model_info")) {
            JsonNode name = info.get("name");
            if (name != null && name.isString() && !name.asString().isBlank()) {
                return name.asString();
            }
        }
        return null;
    }
}
