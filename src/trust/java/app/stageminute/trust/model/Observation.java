package app.stageminute.trust.model;

import app.stageminute.analysis.score.StepStatus;
import app.stageminute.analysis.score.TakeFacts;
import java.util.List;
import java.util.Optional;

/**
 * What one run of one take through the real pipeline produced: labels, times and numbers, never words.
 *
 * @param scored false for a take the no-score gate stopped (then the label fields are empty and the total is 0)
 * @param status the report status word
 * @param steps the four step labels
 * @param total the score total built by code
 * @param fillerStartsMs when each confirmed filler starts
 * @param pauses the recorded pauses
 * @param grammarSpans the stretch of the take each grammar item is about, in milliseconds
 * @param fixCode the fix the report put first, or empty
 * @param words words heard
 * @param averageWpm the take's average pace
 * @param windowWpm the pace of each 10 s window
 * @param measuredDurationMs the length the server measured
 * @param waitMs the time the whole analysis took, upload to report
 * @param costMicros what the vendors charged for this run, in millionths of a dollar
 * @param judgeMs the part of the wait spent in the Gemini labels call alone, 0 when it was not called or not timed
 * @param facts the facts record, for the label-flip noise measure
 */
public record Observation(
        boolean scored,
        String status,
        List<StepStatus> steps,
        int total,
        List<Integer> fillerStartsMs,
        List<PausePoint> pauses,
        List<Span> grammarSpans,
        Optional<String> fixCode,
        int words,
        int averageWpm,
        List<Integer> windowWpm,
        int measuredDurationMs,
        long waitMs,
        long costMicros,
        Optional<TakeFacts> facts,
        long judgeMs) {

    /** A run with no separate Gemini timing (the judge time is 0, "not timed"). */
    public Observation(
            boolean scored,
            String status,
            List<StepStatus> steps,
            int total,
            List<Integer> fillerStartsMs,
            List<PausePoint> pauses,
            List<Span> grammarSpans,
            Optional<String> fixCode,
            int words,
            int averageWpm,
            List<Integer> windowWpm,
            int measuredDurationMs,
            long waitMs,
            long costMicros,
            Optional<TakeFacts> facts) {
        this(
                scored,
                status,
                steps,
                total,
                fillerStartsMs,
                pauses,
                grammarSpans,
                fixCode,
                words,
                averageWpm,
                windowWpm,
                measuredDurationMs,
                waitMs,
                costMicros,
                facts,
                0L);
    }

    /** This run with the time its Gemini call took. */
    public Observation withJudgeMs(long ms) {
        return new Observation(
                scored,
                status,
                steps,
                total,
                fillerStartsMs,
                pauses,
                grammarSpans,
                fixCode,
                words,
                averageWpm,
                windowWpm,
                measuredDurationMs,
                waitMs,
                costMicros,
                facts,
                ms);
    }

    public Observation {
        steps = List.copyOf(steps);
        fillerStartsMs = List.copyOf(fillerStartsMs);
        pauses = List.copyOf(pauses);
        grammarSpans = List.copyOf(grammarSpans);
        windowWpm = List.copyOf(windowWpm);
    }
}
