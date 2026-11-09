package app.stageminute.trust.gates;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.trust.measures.ScoreSpread;
import app.stageminute.trust.measures.WaitTimes;
import app.stageminute.trust.stats.Interval;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class GatesTest {

    private static final Interval RANGE = new Interval(0.80, 0.95);

    @Test
    void passLinesAreTheOnesInSectionOneOfTheQualityGatesDocument() {
        assertThat(PassLines.FILLER_PRECISION_MIN).isEqualTo(0.90);
        assertThat(PassLines.STEP_AGREEMENT_MIN).isEqualTo(0.85);
        assertThat(PassLines.RUN_TO_RUN_MIN).isEqualTo(0.90);
        assertThat(PassLines.RUNS_PER_CLIP).isEqualTo(5);
        assertThat(PassLines.GRAMMAR_ON_ASR_MAX).isEqualTo(0.05);
        assertThat(PassLines.FAIRNESS_MAX_POINTS).isEqualTo(3.0);
        assertThat(PassLines.SCORE_SD_MAX).isEqualTo(3.0);
        assertThat(PassLines.NOISE_BAND_POINTS).isEqualTo(7);
        assertThat(PassLines.P50_MAX_SECONDS).isEqualTo(8.0);
        assertThat(PassLines.P95_MAX_SECONDS).isEqualTo(15.0);
    }

    @Test
    void fillerPrecisionPassesAtTheLineAndFailsJustBelow() {
        assertThat(Gates.fillerPrecision(0.90, RANGE).status()).isEqualTo(Status.PASS);
        assertThat(Gates.fillerPrecision(0.899, RANGE).status()).isEqualTo(Status.FAIL);
        assertThat(Gates.fillerPrecision(Double.NaN, Interval.NONE).status()).isEqualTo(Status.NOT_MEASURED);
    }

    @Test
    void aFailIsNeverRoundedIntoAPass() {
        // 89.96 % prints as 90.0 % at one decimal, but it is below 0.90 and must stay a fail.
        Gate gate = Gates.fillerPrecision(0.8996, RANGE);

        assertThat(gate.status()).isEqualTo(Status.FAIL);
        assertThat(gate.measured()).isEqualTo("89.96 %");
    }

    @Test
    void theGateLinePrintsTheMeasuredNumberBesideThePassLineAndItsRange() {
        Gate gate = Gates.fillerPrecision(0.92, RANGE);

        assertThat(gate.measured()).isEqualTo("92.00 %");
        assertThat(gate.range()).isEqualTo("80.00 % to 95.00 %");
        assertThat(gate.passLine()).isEqualTo("at least 90 %");
        assertThat(gate.source()).contains("11").contains("section 1");
    }

    @Test
    void stepAgreementPrintsKappaBesideItAndKeepsTheLineOnRawAgreement() {
        Gate gate = Gates.stepAgreement(0.85, 0.5714, 40, RANGE);

        assertThat(gate.status()).isEqualTo(Status.PASS);
        assertThat(gate.measured()).isEqualTo("85.00 % (kappa 0.571, 40 cells)");
        assertThat(Gates.stepAgreement(0.84, 0.9, 40, RANGE).status()).isEqualTo(Status.FAIL);
        assertThat(Gates.stepAgreement(Double.NaN, Double.NaN, 0, Interval.NONE).status())
                .isEqualTo(Status.NOT_MEASURED);
    }

    @Test
    void runToRunNeedsFiveRunsAndNinetyPercent() {
        assertThat(Gates.runToRun(0.90, 0.8, 5, RANGE).status()).isEqualTo(Status.PASS);
        assertThat(Gates.runToRun(0.89, 0.8, 5, RANGE).status()).isEqualTo(Status.FAIL);
        Gate fewRuns = Gates.runToRun(1.0, 1.0, 3, RANGE);
        assertThat(fewRuns.status()).isEqualTo(Status.NOT_MEASURED);
        assertThat(fewRuns.measured()).contains("3 runs");
        assertThat(Gates.runToRun(Double.NaN, Double.NaN, 5, Interval.NONE).status())
                .isEqualTo(Status.NOT_MEASURED);
    }

    @Test
    void grammarOnRecognitionErrorsMustStayAtFivePercentOrLess() {
        assertThat(Gates.grammarOnAsrErrors(0.05, RANGE).status()).isEqualTo(Status.PASS);
        assertThat(Gates.grammarOnAsrErrors(0.051, RANGE).status()).isEqualTo(Status.FAIL);
        assertThat(Gates.grammarOnAsrErrors(Double.NaN, Interval.NONE).status()).isEqualTo(Status.NOT_MEASURED);
    }

    @Test
    void fairnessMustStayWithinThreePoints() {
        assertThat(Gates.fairness("filler", 3.0, new Interval(1, 5)).status()).isEqualTo(Status.PASS);
        assertThat(Gates.fairness("filler", 3.01, new Interval(1, 5)).status()).isEqualTo(Status.FAIL);
        Gate none = Gates.fairness("pace", Double.NaN, Interval.NONE);
        assertThat(none.status()).isEqualTo(Status.NOT_MEASURED);
        assertThat(none.measured()).isEqualTo("not measured");
        assertThat(none.name()).contains("pace");
        assertThat(Gates.fairness("pause", 2.0, Interval.NONE).range()).isEqualTo("no range");
    }

    @Test
    void repeatRunSpreadNeedsBothTheSdAndTheRange() {
        assertThat(Gates.scoreSpread(new ScoreSpread.Result(3.0, 7.0, 1.0, 4)).status())
                .isEqualTo(Status.PASS);
        assertThat(Gates.scoreSpread(new ScoreSpread.Result(3.1, 4.0, 1.0, 4)).status())
                .isEqualTo(Status.FAIL);
        assertThat(Gates.scoreSpread(new ScoreSpread.Result(2.0, 8.0, 1.0, 4)).status())
                .isEqualTo(Status.FAIL);
        assertThat(Gates.scoreSpread(new ScoreSpread.Result(Double.NaN, Double.NaN, Double.NaN, 0))
                        .status())
                .isEqualTo(Status.NOT_MEASURED);
        assertThat(Gates.scoreSpread(new ScoreSpread.Result(2.0, 5.0, 1.0, 4)).measured())
                .isEqualTo("worst SD 2.00, worst range 5.0 points (4 clips)");
    }

    @Test
    void theExcellentShareIsPrintedWithNoPassOrFail() {
        Gate gate = Gates.excellentShare(0.07, RANGE);

        assertThat(gate.status()).isEqualTo(Status.NOT_SET);
        assertThat(gate.measured()).isEqualTo("7.00 %");
        assertThat(gate.passLine()).isEqualTo("not set (the target is set on the labelled set)");
        assertThat(Gates.excellentShare(Double.NaN, Interval.NONE).status()).isEqualTo(Status.NOT_MEASURED);
    }

    @Test
    void latencyNeedsBothPercentiles() {
        assertThat(Gates.latency(new WaitTimes.Result(8.0, 15.0, 30)).status()).isEqualTo(Status.PASS);
        assertThat(Gates.latency(new WaitTimes.Result(8.1, 10.0, 30)).status()).isEqualTo(Status.FAIL);
        assertThat(Gates.latency(new WaitTimes.Result(5.0, 15.1, 30)).status()).isEqualTo(Status.FAIL);
        assertThat(Gates.latency(new WaitTimes.Result(Double.NaN, Double.NaN, 0))
                        .status())
                .isEqualTo(Status.NOT_MEASURED);
        assertThat(Gates.latency(new WaitTimes.Result(5.0, 9.0, 30)).measured())
                .isEqualTo("p50 5.0 s, p95 9.0 s (30 runs)");
    }

    @Test
    void costIsPrintedAndHasNoVerdictUntilABaselineIsStored() {
        Gate first = Gates.cost(12_000.0, OptionalLong.empty());

        assertThat(first.status()).isEqualTo(Status.NOT_SET);
        assertThat(first.measured()).isEqualTo("USD 0.0120 per analysis");
        assertThat(first.passLine()).contains("measure first");
        assertThat(Gates.cost(12_000.0, OptionalLong.of(12_000)).status()).isEqualTo(Status.PASS);
        assertThat(Gates.cost(12_001.0, OptionalLong.of(12_000)).status()).isEqualTo(Status.FAIL);
        assertThat(Gates.cost(Double.NaN, OptionalLong.of(12_000)).status()).isEqualTo(Status.NOT_MEASURED);
    }

    @Test
    void theVerdictLineSaysLaunchBlockedWhenAnyGateFails() {
        List<Gate> gates = List.of(
                Gates.fillerPrecision(0.95, RANGE),
                Gates.stepAgreement(0.5, 0.2, 10, RANGE),
                Gates.grammarOnAsrErrors(Double.NaN, Interval.NONE));

        String line = Verdict.line(gates, false);

        assertThat(line).startsWith("LAUNCH BLOCKED").contains("step_agreement");
    }

    @Test
    void anUnmeasuredGateIsNotAPass() {
        List<Gate> gates =
                List.of(Gates.fillerPrecision(0.95, RANGE), Gates.grammarOnAsrErrors(Double.NaN, Interval.NONE));

        assertThat(Verdict.line(gates, false)).startsWith("LAUNCH NOT PROVEN").contains("grammar_on_asr_errors");
    }

    @Test
    void onlyWhenEveryGateIsMeasuredAndPassesDoesTheVerdictSayPass() {
        List<Gate> gates = List.of(
                Gates.fillerPrecision(0.95, RANGE),
                Gates.excellentShare(0.05, RANGE),
                Gates.cost(1.0, OptionalLong.empty()));

        assertThat(Verdict.line(gates, false))
                .startsWith("ALL MEASURED GATES PASS")
                .contains("excellent_share");
    }

    @Test
    void aSyntheticRunAlwaysSaysLaunchBlockedWhatEverTheNumbers() {
        List<Gate> gates = List.of(Gates.fillerPrecision(0.99, RANGE));

        assertThat(Verdict.line(gates, true)).startsWith("LAUNCH BLOCKED").contains("SYNTHETIC");
    }
}
