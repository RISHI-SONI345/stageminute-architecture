package app.stageminute.analysis.vendor;

import app.stageminute.analysis.score.FillerCandidate;
import app.stageminute.analysis.score.Style;
import app.stageminute.analysis.score.Transcript;
import java.util.List;
import java.util.Objects;

/**
 * What the one call sees (03 F-029): the topic, the style and the take's words, plus the places code wants labelled.
 * Speech: in memory for this call only; never stored or logged.
 *
 * @param style PREP or STAR
 * @param topicText the topic the person answered
 * @param transcript the words and sentences
 * @param fillerCandidates the candidates judged in context, by id (the call labels each filler, meaningful or hedge)
 * @param runOnSentences positions of the sentences over 40 words (the call says whether they are run-ons)
 */
public record JudgeInput(
        Style style,
        String topicText,
        Transcript transcript,
        List<FillerCandidate> fillerCandidates,
        List<Integer> runOnSentences) {

    public JudgeInput {
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(topicText, "topicText");
        Objects.requireNonNull(transcript, "transcript");
        fillerCandidates = List.copyOf(fillerCandidates);
        runOnSentences = List.copyOf(runOnSentences);
    }

    @Override
    public String toString() {
        return "JudgeInput[redacted]";
    }
}
