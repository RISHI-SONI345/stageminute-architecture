package app.stageminute.trust.model;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One take of one speaker: what the annotators said and what each run of the pipeline produced. A labelled-set take
 * has annotator references and one run; a replay clip has no references and several runs.
 *
 * @param speakerId an anonymous id (the folder name), never a name
 * @param accentGroup the accent group from {@code speaker.json}
 * @param style PREP or STAR
 * @param takeName the audio file name without the folder
 * @param references one per annotator (empty for a replay clip)
 * @param runs one observation per run through the pipeline
 * @param phoneDurationMs the length the phone reported, when the founder has it (C-69)
 */
public record TakeCase(
        String speakerId,
        String accentGroup,
        String style,
        String takeName,
        List<Reference> references,
        List<Observation> runs,
        OptionalInt phoneDurationMs) {

    public TakeCase {
        references = List.copyOf(references);
        runs = List.copyOf(runs);
    }

    /** The first run, if it was scored: the one the labelled-set gates read. */
    public Optional<Observation> firstScored() {
        return runs.stream().filter(Observation::scored).findFirst();
    }

    /** Every scored run. */
    public List<Observation> scoredRuns() {
        return runs.stream().filter(Observation::scored).toList();
    }
}
