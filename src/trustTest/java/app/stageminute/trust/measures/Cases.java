package app.stageminute.trust.measures;

import app.stageminute.analysis.score.StepStatus;
import app.stageminute.analysis.score.TakeFacts;
import app.stageminute.trust.model.Observation;
import app.stageminute.trust.model.PausePoint;
import app.stageminute.trust.model.Reference;
import app.stageminute.trust.model.Span;
import app.stageminute.trust.model.TakeCase;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** Small builders for the measure tests: synthetic numbers only. */
final class Cases {

    static final StepStatus S = StepStatus.STRONG;
    static final StepStatus W = StepStatus.WEAK;
    static final StepStatus M = StepStatus.MISSING;

    private Cases() {}

    /** A scored run with the given steps and total; everything else empty. */
    static Observation run(List<StepStatus> steps, int total) {
        return new Observation(
                true,
                "scored",
                steps,
                total,
                List.of(),
                List.of(),
                List.of(),
                Optional.empty(),
                100,
                120,
                List.of(120, 120),
                60_000,
                1_000,
                0,
                Optional.empty());
    }

    static Observation withFillers(Observation base, Integer... startsMs) {
        return new Observation(
                base.scored(),
                base.status(),
                base.steps(),
                base.total(),
                List.of(startsMs),
                base.pauses(),
                base.grammarSpans(),
                base.fixCode(),
                base.words(),
                base.averageWpm(),
                base.windowWpm(),
                base.measuredDurationMs(),
                base.waitMs(),
                base.costMicros(),
                base.facts());
    }

    static Observation withPauses(Observation base, PausePoint... pauses) {
        return new Observation(
                base.scored(),
                base.status(),
                base.steps(),
                base.total(),
                base.fillerStartsMs(),
                List.of(pauses),
                base.grammarSpans(),
                base.fixCode(),
                base.words(),
                base.averageWpm(),
                base.windowWpm(),
                base.measuredDurationMs(),
                base.waitMs(),
                base.costMicros(),
                base.facts());
    }

    static Observation withGrammar(Observation base, Span... spans) {
        return new Observation(
                base.scored(),
                base.status(),
                base.steps(),
                base.total(),
                base.fillerStartsMs(),
                base.pauses(),
                List.of(spans),
                base.fixCode(),
                base.words(),
                base.averageWpm(),
                base.windowWpm(),
                base.measuredDurationMs(),
                base.waitMs(),
                base.costMicros(),
                base.facts());
    }

    /** Words, average pace and window pace of a run (for pace and length measures). */
    static Observation withPace(Observation base, int words, int averageWpm, List<Integer> windows, int durationMs) {
        return new Observation(
                base.scored(),
                base.status(),
                base.steps(),
                base.total(),
                base.fillerStartsMs(),
                base.pauses(),
                base.grammarSpans(),
                base.fixCode(),
                words,
                averageWpm,
                windows,
                durationMs,
                base.waitMs(),
                base.costMicros(),
                base.facts());
    }

    static Observation withWait(Observation base, long waitMs) {
        return new Observation(
                base.scored(),
                base.status(),
                base.steps(),
                base.total(),
                base.fillerStartsMs(),
                base.pauses(),
                base.grammarSpans(),
                base.fixCode(),
                base.words(),
                base.averageWpm(),
                base.windowWpm(),
                base.measuredDurationMs(),
                waitMs,
                base.costMicros(),
                base.facts());
    }

    static Observation withFacts(Observation base, TakeFacts facts) {
        return new Observation(
                base.scored(),
                base.status(),
                base.steps(),
                base.total(),
                base.fillerStartsMs(),
                base.pauses(),
                base.grammarSpans(),
                base.fixCode(),
                base.words(),
                base.averageWpm(),
                base.windowWpm(),
                base.measuredDurationMs(),
                base.waitMs(),
                base.costMicros(),
                Optional.of(facts));
    }

    static Observation unscored() {
        return new Observation(
                false,
                "short_take",
                List.of(),
                0,
                List.of(),
                List.of(),
                List.of(),
                Optional.empty(),
                10,
                0,
                List.of(),
                15_000,
                500,
                0,
                Optional.empty());
    }

    /** An annotator's labels: steps, fillers, nothing else. */
    static Reference ref(String annotator, List<StepStatus> steps, Integer... fillerStartsMs) {
        return new Reference(
                annotator, List.of(fillerStartsMs), List.of(), steps, List.of(), Optional.empty(), OptionalInt.empty());
    }

    static Reference withAsr(Reference base, Span... spans) {
        return new Reference(
                base.annotator(),
                base.fillerStartsMs(),
                base.pauses(),
                base.steps(),
                List.of(spans),
                base.firstFix(),
                base.wordCount());
    }

    static Reference withPauseLabels(Reference base, PausePoint... pauses) {
        return new Reference(
                base.annotator(),
                base.fillerStartsMs(),
                List.of(pauses),
                base.steps(),
                base.asrErrorSpans(),
                base.firstFix(),
                base.wordCount());
    }

    static Reference withWordCount(Reference base, int words) {
        return new Reference(
                base.annotator(),
                base.fillerStartsMs(),
                base.pauses(),
                base.steps(),
                base.asrErrorSpans(),
                base.firstFix(),
                OptionalInt.of(words));
    }

    /** A labelled take with one run. */
    static TakeCase take(String speaker, String accent, Observation run, Reference... references) {
        return new TakeCase(
                speaker, accent, "PREP", "take-1.m4a", List.of(references), List.of(run), OptionalInt.empty());
    }

    /** A replay clip: no annotators, several runs. */
    static TakeCase clip(String name, List<Observation> runs) {
        return new TakeCase("replay", "none", "PREP", name, List.of(), runs, OptionalInt.empty());
    }

    static List<StepStatus> steps(StepStatus... all) {
        return new ArrayList<>(List.of(all));
    }
}
