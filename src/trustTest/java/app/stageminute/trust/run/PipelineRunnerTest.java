package app.stageminute.trust.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.stageminute.analysis.internal.AnalysisSettings;
import app.stageminute.analysis.internal.Pipeline;
import app.stageminute.analysis.score.Style;
import app.stageminute.analysis.vendor.FakeJudge;
import app.stageminute.analysis.vendor.FakeScenario;
import app.stageminute.analysis.vendor.FakeSpeechToText;
import app.stageminute.platform.TrustSettings;
import app.stageminute.reports.FixTexts;
import app.stageminute.trust.labelset.LabelSetException;
import app.stageminute.trust.labelset.SyntheticSet;
import app.stageminute.trust.model.Observation;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The runner on the fake vendors: what it records for a good take, a take with no score and a failed vendor. */
class PipelineRunnerTest {

    private static PipelineRunner runner(FakeScenario scenario, AtomicLong clock) throws Exception {
        FixTexts texts = FixTexts.parse(
                new String(
                        PipelineRunnerTest.class
                                .getResourceAsStream("/content-fixes/en.json")
                                .readAllBytes(),
                        StandardCharsets.UTF_8),
                JsonMapper.builder().build());
        Pipeline pipeline = new Pipeline(
                new FakeSpeechToText(scenario, 0),
                new FakeJudge(),
                new AnalysisSettings(TrustSettings.launchDefaults()),
                texts);
        // Every reading of the clock moves it on by 250 ms, so the timing is exact and no real time is spent.
        return new PipelineRunner(pipeline, null, () -> clock.addAndGet(250_000_000L));
    }

    @Test
    void aGoodTakeIsRecordedWithItsLabelsFactsAndTiming() throws Exception {
        PipelineRunner runner = runner(FakeScenario.OK, new AtomicLong());

        Observation run = runner.run(SyntheticSet.m4a(50_000), Style.PREP, "A topic.", 60_000);

        assertThat(run.scored()).isTrue();
        assertThat(run.steps()).hasSize(4);
        assertThat(run.total()).isBetween(1, 100);
        assertThat(run.measuredDurationMs()).isEqualTo(50_000);
        assertThat(run.waitMs()).isEqualTo(250);
        assertThat(run.costMicros()).isZero();
        assertThat(run.facts()).isPresent();
        assertThat(run.windowWpm()).isNotEmpty();
        assertThat(run.grammarSpans()).hasSize(1); // the fake judge raises one grammar item
        assertThat(run.fixCode()).isPresent();
    }

    @Test
    void aTakeTheGateStopsIsRecordedWithNoScore() throws Exception {
        PipelineRunner runner = runner(FakeScenario.SHORT_TAKE, new AtomicLong());

        Observation run = runner.run(SyntheticSet.m4a(50_000), Style.PREP, "A topic.", 60_000);

        assertThat(run.scored()).isFalse();
        assertThat(run.status()).isEqualTo("short_take");
        assertThat(run.steps()).isEmpty();
    }

    @Test
    void aVendorFailureIsRecordedAndDoesNotStopTheRun() throws Exception {
        PipelineRunner runner = runner(FakeScenario.VENDOR_FAILURE, new AtomicLong());

        Observation run = runner.run(SyntheticSet.m4a(50_000), Style.PREP, "A topic.", 60_000);

        assertThat(run.scored()).isFalse();
        assertThat(run.status()).startsWith(PipelineRunner.VENDOR_FAILED + ":");
        assertThat(run.waitMs()).isEqualTo(250);
    }

    @Test
    void theVersionsOfTheRunsAreCollected() throws Exception {
        PipelineRunner runner = runner(FakeScenario.OK, new AtomicLong());
        runner.run(SyntheticSet.m4a(50_000), Style.PREP, "A topic.", 60_000);

        var meta = runner.versions("2026-11-02T00:00:00Z", "synthetic");

        assertThat(meta.promptVersion()).isEqualTo("fake-prompt-0");
        assertThat(meta.schemaVersion()).isEqualTo("fake-schema-0");
        assertThat(meta.pinnedModel()).isEqualTo("fake-judge");
        assertThat(meta.formulaVersion()).isNotBlank();
        assertThat(meta.answeredModels()).isEmpty();
        assertThat(meta.mode()).isEqualTo("synthetic");
    }

    @Test
    void aFileThatIsNotM4aIsRefusedByName() throws Exception {
        PipelineRunner runner = runner(FakeScenario.OK, new AtomicLong());

        assertThatThrownBy(() -> runner.run(new byte[] {1, 2, 3}, Style.PREP, "A topic.", 60_000))
                .isInstanceOf(LabelSetException.class)
                .hasMessageContaining("not a readable M4A");
    }
}
