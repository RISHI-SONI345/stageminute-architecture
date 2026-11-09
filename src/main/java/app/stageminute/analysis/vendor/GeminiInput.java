package app.stageminute.analysis.vendor;

import app.stageminute.analysis.score.FillerCandidate;
import app.stageminute.analysis.score.SentenceSpan;
import app.stageminute.analysis.score.TimedWord;
import app.stageminute.analysis.score.Transcript;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns what the one call sees ({@link JudgeInput}) into the JSON text the prompt describes: the style, the topic, the
 * sentences with their word positions, the filler candidates and the long sentences. Text only: there is no audio in the
 * Gemini call (03 F-029; 05 section 5). The result holds speech, so it is built in memory, sent, and never logged.
 */
final class GeminiInput {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * The text to send, and what the answer is checked against.
     *
     * @param json the JSON text of the user message
     * @param sentenceCount how many sentences were sent (valid sentence ids are 0 up to this, excluded)
     * @param candidateIds the filler candidate ids that were sent
     */
    record Rendered(String json, int sentenceCount, Set<Integer> candidateIds) {

        @Override
        public String toString() {
            return "Rendered[redacted]";
        }
    }

    private GeminiInput() {}

    static Rendered render(JudgeInput input) {
        Transcript transcript = input.transcript();
        List<TimedWord> words = transcript.words();
        List<Object> sentences = new ArrayList<>();
        for (int id = 0; id < transcript.sentences().size(); id++) {
            SentenceSpan span = transcript.sentences().get(id);
            Map<String, Object> sentence = new LinkedHashMap<>();
            sentence.put("id", id);
            sentence.put("first_word", span.firstWord());
            sentence.put(
                    "words",
                    words.subList(span.firstWord(), span.lastWord() + 1).stream()
                            .map(TimedWord::text)
                            .toList());
            sentences.add(sentence);
        }
        List<Object> candidates = new ArrayList<>();
        Set<Integer> candidateIds = new HashSet<>();
        for (FillerCandidate candidate : input.fillerCandidates()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", candidate.id());
            entry.put(
                    "text",
                    String.join(
                            " ",
                            words.subList(candidate.firstWord(), candidate.lastWord() + 1).stream()
                                    .map(TimedWord::text)
                                    .toList()));
            entry.put("sentence", sentenceOf(transcript, candidate.firstWord()));
            entry.put("first_word", candidate.firstWord());
            entry.put("last_word", candidate.lastWord());
            candidates.add(entry);
            candidateIds.add(candidate.id());
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("style", input.style().name());
        root.put("topic", input.topicText());
        root.put("sentences", sentences);
        root.put("filler_candidates", candidates);
        root.put("long_sentences", input.runOnSentences());
        return new Rendered(JSON.writeValueAsString(root), sentences.size(), Set.copyOf(candidateIds));
    }

    private static int sentenceOf(Transcript transcript, int word) {
        for (int id = 0; id < transcript.sentences().size(); id++) {
            SentenceSpan span = transcript.sentences().get(id);
            if (word >= span.firstWord() && word <= span.lastWord()) {
                return id;
            }
        }
        return -1;
    }
}
