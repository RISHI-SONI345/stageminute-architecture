package app.stageminute.trust.model;

import app.stageminute.analysis.score.StepStatus;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * What one annotator labelled for one take (F-123): when each filler starts, the pauses, the status of each step, the
 * stretches where the transcript did not match what was said, the right first fix, and how many words were said. Times
 * and counts only: the annotator's words and notes are never read into the harness.
 */
public record Reference(
        String annotator,
        List<Integer> fillerStartsMs,
        List<PausePoint> pauses,
        List<StepStatus> steps,
        List<Span> asrErrorSpans,
        Optional<String> firstFix,
        OptionalInt wordCount) {

    public Reference {
        fillerStartsMs = List.copyOf(fillerStartsMs);
        pauses = List.copyOf(pauses);
        steps = List.copyOf(steps);
        asrErrorSpans = List.copyOf(asrErrorSpans);
    }
}
