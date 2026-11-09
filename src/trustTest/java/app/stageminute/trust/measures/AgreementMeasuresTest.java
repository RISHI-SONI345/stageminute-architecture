package app.stageminute.trust.measures;

import static app.stageminute.trust.measures.Cases.M;
import static app.stageminute.trust.measures.Cases.S;
import static app.stageminute.trust.measures.Cases.W;
import static app.stageminute.trust.measures.Cases.clip;
import static app.stageminute.trust.measures.Cases.ref;
import static app.stageminute.trust.measures.Cases.run;
import static app.stageminute.trust.measures.Cases.steps;
import static app.stageminute.trust.measures.Cases.take;
import static app.stageminute.trust.measures.Cases.unscored;
import static app.stageminute.trust.measures.Cases.withAsr;
import static app.stageminute.trust.measures.Cases.withFillers;
import static app.stageminute.trust.measures.Cases.withGrammar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.stageminute.trust.model.Span;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgreementMeasuresTest {

    // ---- filler precision ----

    private static List<app.stageminute.trust.model.TakeCase> fillerTake() {
        // The pipeline confirmed fillers at 1000, 5000 and 9000 ms.
        // Annotator A marked 1100 and 5100; annotator B marked 1200 and 9100.
        return List.of(take(
                "s1",
                "g1",
                withFillers(run(steps(S, S, W, M), 80), 1000, 5000, 9000),
                ref("A", steps(S, S, W, M), 1100, 5100),
                ref("B", steps(S, S, W, M), 1200, 9100)));
    }

    @Test
    void aFillerCountsWhenBothAnnotatorsMarkedOneNearIt() {
        // 1000 matches both; 5000 only A; 9000 only B. One of three.
        assertThat(FillerPrecision.of(fillerTake(), FillerPrecision.Rule.BOTH)).isCloseTo(1.0 / 3.0, within(1e-12));
    }

    @Test
    void aFillerCountsWhenEitherAnnotatorMarkedOneNearIt() {
        assertThat(FillerPrecision.of(fillerTake(), FillerPrecision.Rule.EITHER))
                .isEqualTo(1.0);
    }

    @Test
    void theToleranceIsInclusive() {
        var exactlyOn = List.of(take(
                "s1",
                "g1",
                withFillers(run(steps(S, S, W, M), 80), 1000),
                ref("A", steps(S, S, W, M), 1500),
                ref("B", steps(S, S, W, M), 500)));
        var justOutside = List.of(take(
                "s1",
                "g1",
                withFillers(run(steps(S, S, W, M), 80), 1000),
                ref("A", steps(S, S, W, M), 1501),
                ref("B", steps(S, S, W, M), 500)));

        assertThat(FillerPrecision.of(exactlyOn, FillerPrecision.Rule.BOTH)).isEqualTo(1.0);
        assertThat(FillerPrecision.of(justOutside, FillerPrecision.Rule.BOTH)).isEqualTo(0.0);
    }

    @Test
    void noConfirmedFillersOrNoAnnotatorsCannotBeMeasured() {
        var none = List.of(take("s1", "g1", run(steps(S, S, W, M), 80), ref("A", steps(S, S, W, M))));
        var noAnnotators = List.of(clip("c", List.of(withFillers(run(steps(S, S, W, M), 80), 1000))));

        assertThat(FillerPrecision.of(none, FillerPrecision.Rule.BOTH)).isNaN();
        assertThat(FillerPrecision.of(noAnnotators, FillerPrecision.Rule.BOTH)).isNaN();
        assertThat(FillerPrecision.of(
                        List.of(take("s", "g", unscored(), ref("A", steps(S, S, W, M)))), FillerPrecision.Rule.BOTH))
                .isNaN();
    }

    // ---- step agreement ----

    @Test
    void stepAgreementIsPooledOverEveryAnnotatorAndStep() {
        // Pipeline S S W M against A: S S W W (3 of 4) and B: S W W M (3 of 4): 6 of 8.
        var cases = List.of(
                take("s1", "g1", run(steps(S, S, W, M), 80), ref("A", steps(S, S, W, W)), ref("B", steps(S, W, W, M))));

        var result = StepAgreement.pipelineVsAnnotators(cases);

        assertThat(result.agreement()).isEqualTo(0.75);
        assertThat(result.cells()).isEqualTo(8);
        // Hand check of kappa: pipeline labels S4 W2 M2, annotator labels S3 W4 M1 over 8 cells;
        // chance = (4x3 + 2x4 + 2x1) / 64 = 0.34375; kappa = (0.75 - 0.34375) / (1 - 0.34375) = 0.619048.
        assertThat(result.kappa()).isCloseTo(0.619048, within(1e-6));
    }

    @Test
    void stepAgreementSkipsTakesWithNoScoreOrNoAnnotators() {
        var cases = List.of(
                take("s1", "g1", unscored(), ref("A", steps(S, S, W, W))),
                clip("c", List.of(run(steps(S, S, W, M), 80))));

        assertThat(StepAgreement.pipelineVsAnnotators(cases).agreement()).isNaN();
        assertThat(StepAgreement.pipelineVsAnnotators(cases).cells()).isZero();
    }

    @Test
    void annotatorsAreComparedWithEachOtherToo() {
        var cases = List.of(
                take("s1", "g1", run(steps(S, S, W, M), 80), ref("A", steps(S, S, W, W)), ref("B", steps(S, W, W, M))));

        // A S S W W against B S W W M: positions 1 and 3 agree.
        assertThat(StepAgreement.annotatorsVsEachOther(cases)).isEqualTo(0.5);
        assertThat(StepAgreement.annotatorsVsEachOther(List.of(clip("c", List.of(run(steps(S, S, W, M), 80))))))
                .isNaN();
    }

    // ---- run to run ----

    @Test
    void runToRunAgreementCountsEveryPairOfRuns() {
        // Runs: S S W M, S S W M, S W W M. Pairs (1,2) 4/4, (1,3) 3/4, (2,3) 3/4: 10 of 12.
        var cases = List.of(clip(
                "c1", List.of(run(steps(S, S, W, M), 80), run(steps(S, S, W, M), 81), run(steps(S, W, W, M), 79))));

        var result = RunToRun.of(cases);

        assertThat(result.agreement()).isCloseTo(10.0 / 12.0, within(1e-12));
        assertThat(result.pairs()).isEqualTo(3);
        // Hand check: over all 6 ordered pairs, labels S10 W8 M6 of 24 on each side; chance = 200 / 576 = 0.347222;
        // kappa = (0.833333 - 0.347222) / (1 - 0.347222) = 0.744681.
        assertThat(result.kappa()).isCloseTo(0.744681, within(1e-6));
    }

    @Test
    void runToRunLooksOnlyAtScoredRunsAndClipsWithTwoOrMore() {
        var cases = List.of(
                clip("one", List.of(run(steps(S, S, W, M), 80))),
                clip("withNoScore", List.of(run(steps(S, S, W, M), 80), unscored())));

        assertThat(RunToRun.of(cases).agreement()).isNaN();
        assertThat(RunToRun.of(cases).pairs()).isZero();
    }

    // ---- grammar items on speech-recognition errors ----

    @Test
    void aGrammarItemOnARecognitionErrorOfEitherAnnotatorCounts() {
        var observation = withGrammar(
                run(steps(S, S, W, M), 80),
                new Span(1000, 1500),
                new Span(3000, 3400),
                new Span(5000, 5200),
                new Span(7000, 7100));
        var cases = List.of(take(
                "s1",
                "g1",
                observation,
                withAsr(ref("A", steps(S, S, W, M)), new Span(1400, 1800)),
                withAsr(ref("B", steps(S, S, W, M)), new Span(7050, 7300))));

        // Items 1 and 4 overlap an error span: 2 of 4.
        assertThat(GrammarOnAsrErrors.of(cases)).isEqualTo(0.5);
    }

    @Test
    void noGrammarItemsMeansNothingToMeasure() {
        var cases = List.of(take("s1", "g1", run(steps(S, S, W, M), 80), ref("A", steps(S, S, W, M))));

        assertThat(GrammarOnAsrErrors.of(cases)).isNaN();
    }
}
