package app.stageminute.trust.run;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.analysis.internal.AnalysisSettings;
import app.stageminute.analysis.internal.Pipeline;
import app.stageminute.analysis.score.Style;
import app.stageminute.analysis.vendor.FakeJudge;
import app.stageminute.analysis.vendor.FakeScenario;
import app.stageminute.analysis.vendor.FakeSpeechToText;
import app.stageminute.platform.TrustSettings;
import app.stageminute.reports.FixTexts;
import app.stageminute.trust.labelset.SyntheticSet;
import app.stageminute.trust.measures.WaitTimes;
import app.stageminute.trust.model.Observation;
import app.stageminute.trust.model.TakeCase;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The second stopwatch: the Gemini call timed alone, and the wait split into that and the rest. */
class GeminiTimingTest {

    private static final long STEP_NANOS = 250_000_000L;

    private static PipelineRunner runner(FakeScenario scenario) throws Exception {
        FixTexts texts = FixTexts.parse(
                new String(
                        GeminiTimingTest.class
                                .getResourceAsStream("/content-fixes/en.json")
                                .readAllBytes(),
                        StandardCharsets.UTF_8),
                JsonMapper.builder().build());
        // Two clocks, each moving 250 ms on every reading: the whole run reads one, the judge's stopwatch the other.
        AtomicLong runClock = new AtomicLong();
        AtomicLong judgeClock = new AtomicLong();
        TimedJudge timed = new TimedJudge(new FakeJudge(), () -> judgeClock.addAndGet(STEP_NANOS));
        Pipeline pipeline = new Pipeline(
                new FakeSpeechToText(scenario, 0), timed, new AnalysisSettings(TrustSettings.launchDefaults()), texts);
        return new PipelineRunner(pipeline, null, () -> runClock.addAndGet(STEP_NANOS), timed);
    }

    @Test
    void aRunRecordsHowLongTheGeminiCallTookOnItsOwn() throws Exception {
        Observation run = runner(FakeScenario.OK).run(SyntheticSet.m4a(50_000), Style.PREP, "A topic.", 60_000);

        assertThat(run.judgeMs()).isEqualTo(250); // one call: two readings of the judge's clock, 250 ms apart
        assertThat(run.waitMs()).isEqualTo(250); // the whole run: two readings of the run clock
    }

    @Test
    void aRunThatNeverReachedGeminiHasNoGeminiTime() throws Exception {
        Observation run = runner(FakeScenario.SHORT_TAKE).run(SyntheticSet.m4a(50_000), Style.PREP, "A topic.", 60_000);

        assertThat(run.judgeMs()).isZero();
    }

    @Test
    void theStopwatchResetsAfterEachRun() throws Exception {
        PipelineRunner runner = runner(FakeScenario.OK);

        Observation first = runner.run(SyntheticSet.m4a(50_000), Style.PREP, "A topic.", 60_000);
        Observation second = runner.run(SyntheticSet.m4a(50_000), Style.PREP, "A topic.", 60_000);

        assertThat(first.judgeMs()).isEqualTo(250);
        assertThat(second.judgeMs()).isEqualTo(250); // not 500: the first run's time was handed out and cleared
    }

    @Test
    void theWaitIsSplitIntoGeminiAndTheRestOnlyOverRunsThatReachedGemini() {
        Observation withGemini = observation(10_000, 4_000);
        Observation moreGemini = observation(12_000, 6_000);
        Observation failedInDeepgram = observation(30_000, 0);

        WaitTimes.Result wait = WaitTimes.of(List.of(new TakeCase(
                "c",
                "g",
                "PREP",
                "clip",
                List.of(),
                List.of(withGemini, moreGemini, failedInDeepgram),
                OptionalInt.empty())));

        assertThat(wait.runs()).isEqualTo(3);
        assertThat(wait.judgeRuns()).isEqualTo(2);
        assertThat(wait.judgeP50Seconds()).isEqualTo(4.0);
        assertThat(wait.otherP50Seconds()).isEqualTo(6.0); // 10 s - 4 s on the first run, 6 s on the second
        assertThat(WaitTimes.of(List.of()).judgeRuns()).isZero();
        assertThat(WaitTimes.of(List.of()).judgeP50Seconds()).isNaN();
    }

    private static Observation observation(long waitMs, long judgeMs) {
        return new Observation(
                        true,
                        "scored",
                        List.of(),
                        50,
                        List.of(),
                        List.of(),
                        List.of(),
                        java.util.Optional.empty(),
                        100,
                        120,
                        List.of(120),
                        60_000,
                        waitMs,
                        0,
                        java.util.Optional.empty())
                .withJudgeMs(judgeMs);
    }
}
