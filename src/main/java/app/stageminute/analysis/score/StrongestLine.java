package app.stageminute.analysis.score;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The strongest line (03 F-035): the model chooses a sentence id, and code maps its times and rejects it when any word
 * of the sentence has a confidence under the line (0.5), because a quote must be heard clearly. The line is hidden
 * (empty) when the model chose none, when the id is not a sentence, or when it is the same place as the fix moment.
 * Pure: no clock, no user, no tier.
 */
public final class StrongestLine {

    private StrongestLine() {}

    /**
     * Maps the model's choice to times, or hides it.
     *
     * @param chosenSentenceId the model's choice, empty when it chose none
     * @param fixMoment the place the first fix is about, if it has one
     */
    public static Optional<StrongestLineResult> pick(
            OptionalInt chosenSentenceId, Transcript transcript, Optional<Moment> fixMoment, MetricsSettings settings) {
        if (chosenSentenceId.isEmpty()) {
            return Optional.empty();
        }
        int id = chosenSentenceId.getAsInt();
        if (id < 0 || id >= transcript.sentences().size()) {
            return Optional.empty();
        }
        SentenceSpan sentence = transcript.sentences().get(id);
        List<TimedWord> words = transcript.words().subList(sentence.firstWord(), sentence.lastWord() + 1);
        if (words.stream().anyMatch(word -> word.confidence() < settings.lowConfidenceBelow())) {
            return Optional.empty();
        }
        int startMs = words.get(0).startMs();
        int endMs = words.get(words.size() - 1).endMs();
        if (fixMoment.isPresent() && fixMoment.get().overlaps(startMs, endMs)) {
            return Optional.empty();
        }
        return Optional.of(new StrongestLineResult(id, sentence.firstWord(), sentence.lastWord(), startMs, endMs));
    }
}
