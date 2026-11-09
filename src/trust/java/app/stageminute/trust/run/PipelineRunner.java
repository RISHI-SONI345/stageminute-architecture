package app.stageminute.trust.run;

import app.stageminute.analysis.internal.Pipeline;
import app.stageminute.analysis.internal.TrustAudio;
import app.stageminute.analysis.score.StepStatus;
import app.stageminute.analysis.score.Style;
import app.stageminute.analysis.vendor.VendorUnavailable;
import app.stageminute.reports.ReportV2;
import app.stageminute.trust.labelset.LabelSetException;
import app.stageminute.trust.model.Observation;
import app.stageminute.trust.model.PausePoint;
import app.stageminute.trust.model.SavedRun;
import app.stageminute.trust.model.Span;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Runs the real {@link Pipeline} in-process (so the harness measures the real code and can read the facts record, the
 * validated labels and the timings) and turns each result into an {@link Observation}: labels, times and numbers, never
 * words. It also times the whole analysis and attaches what the vendors charged. It collects the versions that made the
 * runs (formula, prompt, schema, pinned model and the model ids the vendor answered with).
 */
public final class PipelineRunner implements TakeRunner {

    private static final int FIRST_ATTEMPT = 1;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    /** The start of the status of a run whose vendor call failed (then a colon and the fixed failure code). */
    public static final String VENDOR_FAILED = "vendor_unavailable";

    private final Pipeline pipeline;
    private final UsageMeter meter;
    private final TimedJudge judgeTimer;
    private final LongSupplier nanoClock;
    private final Set<String> answeredModels = new LinkedHashSet<>();
    private String formulaVersion = "";
    private String promptVersion = "";
    private String schemaVersion = "";
    private String pinnedModel = "";

    public PipelineRunner(Pipeline pipeline, UsageMeter meter, LongSupplier nanoClock) {
        this(pipeline, meter, nanoClock, null);
    }

    /** With a second stopwatch on the Gemini call ({@code judgeTimer} wraps the judge the pipeline uses; may be null). */
    public PipelineRunner(Pipeline pipeline, UsageMeter meter, LongSupplier nanoClock, TimedJudge judgeTimer) {
        this.pipeline = pipeline;
        this.meter = meter;
        this.nanoClock = nanoClock;
        this.judgeTimer = judgeTimer;
    }

    @Override
    public Observation run(byte[] audio, Style style, String topic, int capMs) {
        int durationMs = TrustAudio.durationMs(audio)
                .orElseThrow(() -> new LabelSetException("a recording is not a readable M4A file"));
        long started = nanoClock.getAsLong();
        Pipeline.Result result;
        try {
            result = pipeline.run(new Pipeline.Input(
                    audio,
                    durationMs,
                    capMs,
                    style,
                    topic,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    FIRST_ATTEMPT,
                    Optional.empty(),
                    Optional.empty(),
                    List.of()));
        } catch (VendorUnavailable e) {
            // One failed call must not throw away a whole paid run: it is recorded as a take with no score, and the
            // report counts it. The message is a fixed code, never vendor text or speech.
            long failedMs = (nanoClock.getAsLong() - started) / NANOS_PER_MILLI;
            return new Observation(
                    false,
                    VENDOR_FAILED + ":" + e.getMessage(),
                    List.of(),
                    0,
                    List.of(),
                    List.of(),
                    List.of(),
                    Optional.empty(),
                    0,
                    0,
                    List.of(),
                    durationMs,
                    failedMs,
                    meter == null ? 0 : meter.drain(),
                    Optional.empty(),
                    judgeTimer == null ? 0 : judgeTimer.drainMs());
        }
        long waitMs = (nanoClock.getAsLong() - started) / NANOS_PER_MILLI;
        return observe(result, durationMs, waitMs, meter == null ? 0 : meter.drain())
                .withJudgeMs(judgeTimer == null ? 0 : judgeTimer.drainMs());
    }

    /** What the runs so far were made with; the answered models are the ones the vendor reported. */
    public SavedRun.Meta versions(String date, String mode) {
        return new SavedRun.Meta(
                date, mode, formulaVersion, promptVersion, schemaVersion, pinnedModel, List.copyOf(answeredModels));
    }

    private Observation observe(Pipeline.Result result, int durationMs, long waitMs, long costMicros) {
        ReportV2 report = result.report();
        formulaVersion = report.formulaVersion();
        promptVersion = report.promptVersion();
        schemaVersion = report.schemaVersion();
        pinnedModel = report.modelVersion();
        List<PausePoint> pauses = report.facts().pauses().stream()
                .map(pause -> new PausePoint(pause.startMs(), pause.lengthMs()))
                .toList();
        List<Integer> fillers = report.facts().fillers().items().stream()
                .map(ReportV2.FillerItem::startMs)
                .toList();
        if (report.score() == null || result.scored().isEmpty()) {
            return new Observation(
                    false,
                    report.status().name().toLowerCase(Locale.ROOT),
                    List.of(),
                    0,
                    fillers,
                    pauses,
                    List.of(),
                    Optional.empty(),
                    report.facts().words(),
                    0,
                    List.of(),
                    durationMs,
                    waitMs,
                    costMicros,
                    Optional.empty());
        }
        Pipeline.Scored scored = result.scored().get();
        if (scored.answeredJudgeModel() != null) {
            answeredModels.add(scored.answeredJudgeModel());
        }
        List<StepStatus> steps = report.findings().steps().stream()
                .map(step -> StepStatus.valueOf(step.status().name()))
                .toList();
        return new Observation(
                true,
                report.status().name().toLowerCase(Locale.ROOT),
                steps,
                report.score().total(),
                fillers,
                pauses,
                grammarSpans(report, result),
                Optional.ofNullable(report.fixFirst()).map(ReportV2.FixFirst::id),
                report.facts().words(),
                scored.averageWpm(),
                scored.facts().windowWpm(),
                durationMs,
                waitMs,
                costMicros,
                Optional.of(scored.facts()));
    }

    /** The stretch of the take each grammar item is about, from the first word's start to the last word's end. */
    private static List<Span> grammarSpans(ReportV2 report, Pipeline.Result result) {
        List<Span> spans = new ArrayList<>();
        var words = result.speech().words();
        for (ReportV2.LanguageItem item : report.findings().languageItems()) {
            if (item.kind() != ReportV2.LanguageKind.GRAMMAR
                    || item.firstWord() < 0
                    || item.lastWord() >= words.size()
                    || item.lastWord() < item.firstWord()) {
                continue;
            }
            spans.add(new Span(
                    words.get(item.firstWord()).startMs(),
                    words.get(item.lastWord()).endMs()));
        }
        return spans;
    }

    /** A runner that times with the system clock. */
    public static PipelineRunner of(Pipeline pipeline, UsageMeter meter) {
        return new PipelineRunner(pipeline, meter, System::nanoTime);
    }

    /** A runner that times with the system clock and also times the Gemini call alone. */
    public static PipelineRunner of(Pipeline pipeline, UsageMeter meter, TimedJudge judgeTimer) {
        return new PipelineRunner(pipeline, meter, System::nanoTime, judgeTimer);
    }
}
