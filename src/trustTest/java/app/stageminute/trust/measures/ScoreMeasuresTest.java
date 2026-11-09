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
import static app.stageminute.trust.measures.Cases.withFacts;
import static app.stageminute.trust.measures.Cases.withFillers;
import static app.stageminute.trust.measures.Cases.withPace;
import static app.stageminute.trust.measures.Cases.withPauseLabels;
import static app.stageminute.trust.measures.Cases.withPauses;
import static app.stageminute.trust.measures.Cases.withWait;
import static app.stageminute.trust.measures.Cases.withWordCount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import app.stageminute.analysis.score.LanguageLevels;
import app.stageminute.analysis.score.PaceBand;
import app.stageminute.analysis.score.StepStatus;
import app.stageminute.analysis.score.Style;
import app.stageminute.analysis.score.TakeFacts;
import app.stageminute.trust.model.Observation;
import app.stageminute.trust.model.PausePoint;
import app.stageminute.trust.model.TakeCase;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class ScoreMeasuresTest {

    // ---- repeat-run score spread ----

    @Test
    void spreadIsReadPerClipAndTheWorstClipDecides() {
        // Totals 80 82 79 85 81: mean 81.4, squared gaps 1.96 + 0.36 + 5.76 + 12.96 + 0.16 = 21.2,
        // sample variance 21.2 / 4 = 5.3, SD 2.302; range 85 - 79 = 6. The second clip does not move.
        var spread =
                ScoreSpread.of(List.of(clip("a", totals(80, 82, 79, 85, 81)), clip("b", totals(70, 70, 70, 70, 70))));

        assertThat(spread.worstSd()).isCloseTo(Math.sqrt(5.3), within(1e-12));
        assertThat(spread.worstRange()).isEqualTo(6.0);
        assertThat(spread.meanSd()).isCloseTo(Math.sqrt(5.3) / 2, within(1e-12));
        assertThat(spread.clips()).isEqualTo(2);
    }

    @Test
    void aClipWithFewerThanTwoScoredRunsHasNoSpread() {
        var spread = ScoreSpread.of(List.of(clip("a", List.of(run(steps(S, S, W, M), 80), unscored()))));

        assertThat(spread.clips()).isZero();
        assertThat(spread.worstSd()).isNaN();
    }

    private static List<Observation> totals(int... values) {
        List<Observation> runs = new ArrayList<>();
        for (int value : values) {
            runs.add(run(steps(S, S, W, M), value));
        }
        return runs;
    }

    // ---- excellent share ----

    @Test
    void excellentIsNinetyAndAbove() {
        var cases = List.of(
                take("s", "g", run(steps(S, S, S, S), 91)),
                take("s", "g", run(steps(S, S, S, S), 90)),
                take("s", "g", run(steps(S, S, S, S), 85)),
                take("s", "g", run(steps(S, S, S, S), 70)),
                take("s", "g", unscored()));

        assertThat(ExcellentShare.of(cases)).isEqualTo(0.5);
        assertThat(ExcellentShare.of(List.of())).isNaN();
    }

    // ---- wait ----

    @Test
    void waitIsReportedAsP50AndP95InSeconds() {
        List<Observation> runs = new ArrayList<>();
        for (int second = 1; second <= 20; second++) {
            runs.add(withWait(run(steps(S, S, W, M), 80), second * 1000L));
        }

        var wait = WaitTimes.of(List.of(clip("c", runs)));

        assertThat(wait.p50Seconds()).isEqualTo(10.0); // rank 10 of 20
        assertThat(wait.p95Seconds()).isEqualTo(19.0); // rank 19 of 20
        assertThat(wait.runs()).isEqualTo(20);
        assertThat(WaitTimes.of(List.of()).p50Seconds()).isNaN();
    }

    // ---- take length ----

    @Test
    void serverLengthIsComparedWithThePhonesWhenWeHaveIt() {
        TakeCase a =
                withPhone(take("s", "g", withPace(run(steps(S, S, W, M), 80), 100, 120, List.of(120), 60_000)), 59_500);
        TakeCase b =
                withPhone(take("s", "g", withPace(run(steps(S, S, W, M), 80), 100, 120, List.of(120), 30_200)), 30_000);
        TakeCase c = take("s", "g", withPace(run(steps(S, S, W, M), 80), 100, 120, List.of(120), 20_000));

        var length = TakeLength.of(List.of(a, b, c));

        assertThat(length.compared()).isEqualTo(2);
        assertThat(length.meanAbsDiffMs()).isEqualTo(350.0);
        assertThat(length.maxAbsDiffMs()).isEqualTo(500);
        assertThat(length.noPhoneValue()).isEqualTo(1);
    }

    @Test
    void noPhoneValueAnywhereIsSaidPlainly() {
        var length = TakeLength.of(List.of(take("s", "g", run(steps(S, S, W, M), 80))));

        assertThat(length.compared()).isZero();
        assertThat(length.meanAbsDiffMs()).isNaN();
    }

    private static TakeCase withPhone(TakeCase base, int phoneMs) {
        return new TakeCase(
                base.speakerId(),
                base.accentGroup(),
                base.style(),
                base.takeName(),
                base.references(),
                base.runs(),
                OptionalInt.of(phoneMs));
    }

    // ---- length bias ----

    @Test
    void longerTakesWithMoreStrongStepsGiveAPositiveCorrelation() {
        var cases = List.of(
                take("s", "g", withPace(run(steps(W, W, M, M), 40), 50, 100, List.of(100), 30_000)),
                take("s", "g", withPace(run(steps(S, W, M, M), 50), 80, 100, List.of(100), 45_000)),
                take("s", "g", withPace(run(steps(S, S, W, M), 60), 110, 100, List.of(100), 60_000)),
                take("s", "g", withPace(run(steps(S, S, S, W), 70), 140, 100, List.of(100), 75_000)));

        // Strong counts 0, 1, 2, 3 against lengths 30, 45, 60, 75 s: a straight line.
        assertThat(LengthBias.of(cases)).isCloseTo(1.0, within(1e-12));
        assertThat(LengthBias.of(cases.subList(0, 2))).isNaN();
    }

    // ---- pace and pause accuracy (for the fairness gates) ----

    @Test
    void paceAccuracyIsHowCloseTheWordCountIsToTheAnnotators() {
        var observation = withPace(run(steps(S, S, W, M), 80), 95, 120, List.of(120), 60_000);
        var cases = List.of(take(
                "s",
                "g",
                observation,
                withWordCount(ref("A", steps(S, S, W, M)), 100),
                withWordCount(ref("B", steps(S, S, W, M)), 100)));

        assertThat(PaceAccuracy.of(cases)).isCloseTo(0.95, within(1e-12));
        assertThat(PaceAccuracy.of(List.of(take("s", "g", observation, ref("A", steps(S, S, W, M))))))
                .isNaN();
    }

    @Test
    void pauseAccuracyIsTheF1OfRecordedPausesAgainstTheAnnotators() {
        // Pipeline pauses at 1000 and 6000; the annotator marked 1100 and 9000. One match each way: P = R = 0.5.
        var observation =
                withPauses(run(steps(S, S, W, M), 80), new PausePoint(1000, 1500), new PausePoint(6000, 5200));
        var cases = List.of(take(
                "s",
                "g",
                observation,
                withPauseLabels(ref("A", steps(S, S, W, M)), new PausePoint(1100, 1400), new PausePoint(9000, 2000))));

        assertThat(PauseMatch.f1(cases)).isCloseTo(0.5, within(1e-12));
        assertThat(PauseMatch.f1(List.of(take("s", "g", run(steps(S, S, W, M), 80), ref("A", steps(S, S, W, M))))))
                .isNaN();
    }

    // ---- fairness across accent groups ----

    @Test
    void fairnessIsTheSpreadBetweenTheBestAndWorstGroupInPoints() {
        var cases = List.of(
                take("s1", "indian", withFillers(run(steps(S, S, W, M), 80), 1000), ref("A", steps(S, S, W, M), 1000)),
                take(
                        "s2",
                        "british",
                        withFillers(run(steps(S, S, W, M), 80), 1000),
                        ref("A", steps(S, S, W, M), 9000)));

        double spread = Fairness.spreadPoints(cases, group -> FillerPrecision.of(group, FillerPrecision.Rule.EITHER));

        assertThat(spread).isEqualTo(100.0); // indian 1.0, british 0.0
        assertThat(Fairness.byGroup(cases, group -> FillerPrecision.of(group, FillerPrecision.Rule.EITHER)))
                .containsEntry("indian", 1.0)
                .containsEntry("british", 0.0);
    }

    @Test
    void oneGroupOnlyCannotShowFairness() {
        var cases = List.of(take(
                "s1", "indian", withFillers(run(steps(S, S, W, M), 80), 1000), ref("A", steps(S, S, W, M), 1000)));

        assertThat(Fairness.spreadPoints(cases, group -> FillerPrecision.of(group, FillerPrecision.Rule.EITHER)))
                .isNaN();
    }

    // ---- pace bands ----

    private static TakeCase paceTake(int wpm) {
        return take("s1", "g", withPace(run(steps(S, S, W, M), 80), 100, wpm, List.of(wpm, wpm, wpm), 30_000));
    }

    @Test
    void paceBandsAreComparedPerSpeakerLeavingTheTakeOut() {
        // Average paces 100, 120, 160, 200. Sample SD = sqrt(5900 / 3) = 44.35.
        // Launch band 85 to 240: all four takes get full pace points.
        // Personal band (median of the other takes +- 25): 135-185, 135-185, 95-145, 95-145: none holds its take.
        // Mean +- 1 SD of the other takes: 120-200, 103-204, 87-193, 96-157: the 2nd and 3rd hold their take.
        var speakers = PaceBands.compare(List.of(paceTake(100), paceTake(120), paceTake(160), paceTake(200)));

        assertThat(speakers).hasSize(1);
        PaceBands.Speaker speaker = speakers.get(0);
        assertThat(speaker.takes()).isEqualTo(4);
        assertThat(speaker.paceSd()).isCloseTo(Math.sqrt(5900.0 / 3.0), within(1e-9));
        assertThat(speaker.fullLaunch()).isEqualTo(4);
        assertThat(speaker.fullPersonal()).isZero();
        assertThat(speaker.fullMeanSd()).isEqualTo(2);

        PaceBands.Totals totals = PaceBands.totals(speakers);
        assertThat(totals.takes()).isEqualTo(4);
        assertThat(totals.fullLaunch()).isEqualTo(4);
        assertThat(totals.fullPersonal()).isZero();
        assertThat(totals.fullMeanSd()).isEqualTo(2);
    }

    @Test
    void aTakeWithoutEnoughOtherTakesFallsBackToTheLaunchBand() {
        var speakers = PaceBands.compare(List.of(paceTake(100)));

        assertThat(speakers.get(0).fullLaunch()).isEqualTo(1);
        assertThat(speakers.get(0).fullPersonal()).isEqualTo(1);
        assertThat(speakers.get(0).fullMeanSd()).isEqualTo(1);
        assertThat(speakers.get(0).paceSd()).isEqualTo(0.0);
    }

    // ---- label-flip noise ----

    private static TakeFacts perfectPrepTake() {
        return new TakeFacts(
                Style.PREP,
                List.of(StepStatus.STRONG, StepStatus.STRONG, StepStatus.STRONG, StepStatus.STRONG),
                4,
                new LanguageLevels(4, 4, 4),
                150,
                55_000,
                60_000,
                800,
                0,
                1_300,
                0,
                0,
                20,
                false,
                List.of(150, 150, 150, 150, 150, 150),
                PaceBand.LAUNCH_DEFAULT,
                false);
    }

    @Test
    void oneLabelFlipIsCountedWhenItMovesTheTotalByTheNoiseBandOrMore() {
        // A perfect PREP take totals 100 (Structure 45, Language 35, Delivery 20).
        // Step strong to weak costs half a step weight: Point 6.75 -> total 93 (moves 7: crosses), Reason 4.5 -> 96,
        // Example and Closing 5.625 -> 94 (moves 6): one crossing. Strong to missing costs the whole weight:
        // Point 13.5 -> 86, Reason 9 -> 91 (moves 9), Example and Closing 11.25 -> 89 (moves 11): all four cross.
        // One Language level down (4 to 3) costs 3.5 -> 97 (moves 3); relevance 4 to 3 changes nothing.
        var result = LabelFlip.noise(List.of(perfectPrepTake()), 7);

        assertThat(result.flips()).isEqualTo(12);
        assertThat(result.crossings()).isEqualTo(5);
        assertThat(result.share()).isCloseTo(5.0 / 12.0, within(1e-12));
        assertThat(result.byKind().get(LabelFlip.Kind.STEP_ONE_LEVEL).flips()).isEqualTo(4);
        assertThat(result.byKind().get(LabelFlip.Kind.STEP_ONE_LEVEL).crossings())
                .isEqualTo(1);
        assertThat(result.byKind().get(LabelFlip.Kind.STEP_TWO_LEVELS).crossings())
                .isEqualTo(4);
        assertThat(result.byKind().get(LabelFlip.Kind.CLARITY).flips()).isEqualTo(1);
        assertThat(result.byKind().get(LabelFlip.Kind.CLARITY).crossings()).isZero();
        assertThat(result.byKind().get(LabelFlip.Kind.RELEVANCE).crossings()).isZero();
    }

    @Test
    void takesWithoutFactsAreSkippedAndNothingToFlipGivesNoShare() {
        var cases = List.of(take("s", "g", unscored()), take("s", "g", run(steps(S, S, W, M), 80)));

        var result = LabelFlip.noiseOf(cases, 7);

        assertThat(result.flips()).isZero();
        assertThat(result.share()).isNaN();
        assertThat(LabelFlip.noiseOf(
                                List.of(take("s", "g", withFacts(run(steps(S, S, S, S), 100), perfectPrepTake()))), 7)
                        .flips())
                .isEqualTo(12);
    }

    @Test
    void theRebuiltTotalMatchesTheTotalTheScoreCodeGaveTheTake() {
        assertThat(LabelFlip.total(perfectPrepTake())).isEqualTo(100);
    }
}
