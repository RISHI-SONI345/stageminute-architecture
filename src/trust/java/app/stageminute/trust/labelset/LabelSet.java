package app.stageminute.trust.labelset;

import app.stageminute.analysis.score.Style;
import app.stageminute.trust.model.Reference;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;

/**
 * The labelled speaker set read from its folder (F-123). Only the folder layout and the numbers are read: the harness
 * never reads a name, a note or a transcript from it.
 *
 * @param speakers speakers with a valid consent, ready to run
 * @param excluded speakers left out, and why (an anonymous id and a reason, never a name)
 */
public record LabelSet(List<SpeakerFolder> speakers, List<Excluded> excluded) {

    public LabelSet {
        speakers = List.copyOf(speakers);
        excluded = List.copyOf(excluded);
    }

    /** A speaker's folder. */
    public record SpeakerFolder(
            String id,
            Path dir,
            String accentGroup,
            Style style,
            String topic,
            int capMs,
            String consentId,
            List<String> annotators,
            List<TakeInput> takes) {

        public SpeakerFolder {
            annotators = List.copyOf(annotators);
            takes = List.copyOf(takes);
        }
    }

    /** One audio file with what the annotators said about it. */
    public record TakeInput(String name, Path audio, OptionalInt phoneDurationMs, List<Reference> references) {

        public TakeInput {
            references = List.copyOf(references);
        }
    }

    /** A speaker left out of the run. */
    public record Excluded(String speakerId, String reason) {}

    /** One fixed replay clip. */
    public record ClipInput(String name, Path audio, Style style, String topic, int capMs) {}
}
