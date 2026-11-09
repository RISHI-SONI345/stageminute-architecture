package app.stageminute.analysis.score;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 03 F-032 ("Done when" column) and F-033: the focus check compares the take with the previous attempt's facts, which
 * the caller reads from the server's own {@code facts_record}. One block per row of the table. Expected values are
 * worked by hand from the "Done when" text.
 */
class FocusCheckTest {

    static final FixSettings SETTINGS = FixSettings.LAUNCH_DEFAULT;

    private static FocusCheckResult check(
            FixId fix, int target, UnaryOperator<Takes> previous, UnaryOperator<Takes> current) {
        PreviousTake before = new PreviousTake(
                new FixChoice(fix, target, false, false),
                previous.apply(Takes.strong()).build());
        return FocusCheck.check(before, current.apply(Takes.strong()).build(), SETTINGS);
    }

    private static FocusResult result(
            FixId fix, int target, UnaryOperator<Takes> previous, UnaryOperator<Takes> current) {
        return check(fix, target, previous, current).result();
    }

    // ---- P0 speak_longer: at least 40 words and 25 s
    // -------------------------------------------------------------------

    @Test
    void speakLongerIsDoneAtFortyWordsAndTwentyFiveSeconds() {
        UnaryOperator<Takes> before = t -> t.words(30).speechMs(18_000);

        assertThat(result(FixId.SPEAK_LONGER, -1, before, t -> t.words(40).speechMs(25_000)))
                .isEqualTo(FocusResult.DONE);
        assertThat(result(FixId.SPEAK_LONGER, -1, before, t -> t.words(40).speechMs(24_999)))
                .isEqualTo(FocusResult.NOT_YET);
        assertThat(result(FixId.SPEAK_LONGER, -1, before, t -> t.words(39).speechMs(30_000)))
                .isEqualTo(FocusResult.NOT_YET);
    }

    @Test
    void speakLongerShowsTheSpeechTimeBeforeAndAfter() {
        FocusCheckResult r = check(FixId.SPEAK_LONGER, -1, t -> t.words(30).speechMs(18_000), t -> t.speechMs(26_000));

        assertThat(r.evidence()).isEqualTo(new FocusEvidence(FocusMeasure.SPEECH_MS, 18_000, 26_000));
        assertThat(r.fixId()).isEqualTo(FixId.SPEAK_LONGER);
        assertThat(r.previousStatus()).isEmpty();
    }

    // ---- P1 answer_question: relevance 3 or more
    // -----------------------------------------------------------------------

    @Test
    void answerQuestionIsDoneAtRelevanceThree() {
        assertThat(result(FixId.ANSWER_QUESTION, -1, t -> t.relevance(1), t -> t.relevance(3)))
                .isEqualTo(FocusResult.DONE);
        assertThat(result(FixId.ANSWER_QUESTION, -1, t -> t.relevance(1), t -> t.relevance(2)))
                .isEqualTo(FocusResult.NOT_YET);
    }

    // ---- P2 to P5: strong = Done, weak = Better
    // ------------------------------------------------------------------------

    static Stream<Arguments> stepFixes() {
        return Stream.of(
                Arguments.of(FixId.OPEN_POINT, 0),
                Arguments.of(FixId.SET_SCENE, 0),
                Arguments.of(FixId.ADD_CLOSING, 3),
                Arguments.of(FixId.SAY_RESULT, 3),
                Arguments.of(FixId.ADD_EXAMPLE, 2),
                Arguments.of(FixId.NAME_ACTION, 2),
                Arguments.of(FixId.ADD_REASON, 1),
                Arguments.of(FixId.NAME_TASK, 1));
    }

    @ParameterizedTest(name = "{0}: strong = Done, weak = Better, missing = Not yet")
    @MethodSource("stepFixes")
    void aMissingStepIsDoneWhenStrongAndBetterWhenWeak(FixId fix, int step) {
        UnaryOperator<Takes> before = t -> t.step(step, StepStatus.MISSING);

        assertThat(result(fix, step, before, t -> t.step(step, StepStatus.STRONG)))
                .isEqualTo(FocusResult.DONE);
        assertThat(result(fix, step, before, t -> t.step(step, StepStatus.WEAK)))
                .isEqualTo(FocusResult.BETTER);
        assertThat(result(fix, step, before, t -> t.step(step, StepStatus.MISSING)))
                .isEqualTo(FocusResult.NOT_YET);
    }

    @Test
    void aStepFixShowsThePreviousStatusAndTheStepBeforeAndAfter() {
        FocusCheckResult r =
                check(FixId.ADD_EXAMPLE, 2, t -> t.step(2, StepStatus.MISSING), t -> t.step(2, StepStatus.WEAK));

        assertThat(r.previousStatus()).isEqualTo(Optional.of(StepStatus.MISSING));
        // missing 0, weak 1, strong 2
        assertThat(r.evidence()).isEqualTo(new FocusEvidence(FocusMeasure.STEP_STATUS, 0, 1));
    }

    // ---- P9 strengthen_step: weak -> strong
    // ----------------------------------------------------------------------------

    @Test
    void strengthenStepIsDoneWhenTheStepIsStrong() {
        UnaryOperator<Takes> before = t -> t.step(1, StepStatus.WEAK);

        assertThat(result(FixId.STRENGTHEN_STEP, 1, before, t -> t)).isEqualTo(FocusResult.DONE);
        assertThat(result(FixId.STRENGTHEN_STEP, 1, before, t -> t.step(1, StepStatus.WEAK)))
                .isEqualTo(FocusResult.NOT_YET);
        assertThat(result(FixId.STRENGTHEN_STEP, 1, before, t -> t.step(1, StepStatus.MISSING)))
                .isEqualTo(FocusResult.NOT_YET);
    }

    // ---- wrap_up_in_time
    // ---------------------------------------------------------------------------------------------------

    @Test
    void wrapUpInTimeIsDoneOnceTheClosingIsThere() {
        UnaryOperator<Takes> before = t -> t.step(3, StepStatus.MISSING).hitCap();

        assertThat(result(FixId.WRAP_UP_IN_TIME, 3, before, t -> t)).isEqualTo(FocusResult.DONE);
        // a weak closing under the cap still counts as wrapped up in time
        assertThat(result(
                        FixId.WRAP_UP_IN_TIME,
                        3,
                        before,
                        t -> t.step(3, StepStatus.WEAK).hitCap()))
                .isEqualTo(FocusResult.DONE);
    }

    @Test
    void wrapUpInTimeIsBetterWhenTheTakeEndedNaturallyButStillHasNoClosing() {
        UnaryOperator<Takes> before = t -> t.step(3, StepStatus.MISSING).hitCap();

        assertThat(result(FixId.WRAP_UP_IN_TIME, 3, before, t -> t.step(3, StepStatus.MISSING)))
                .isEqualTo(FocusResult.BETTER);
    }

    @Test
    void wrapUpInTimeIsNotYetWhenTheCapIsHitAgainWithNoClosing() {
        UnaryOperator<Takes> before = t -> t.step(3, StepStatus.MISSING).hitCap();

        assertThat(result(
                        FixId.WRAP_UP_IN_TIME,
                        3,
                        before,
                        t -> t.step(3, StepStatus.MISSING).hitCap()))
                .isEqualTo(FocusResult.NOT_YET);
    }

    // ---- P6 no_freeze: none = Done, longest gap 30% shorter = Better
    // ----------------------------------------------------

    @Test
    void noFreezeIsDoneWhenThereIsNoLongPauseLeft() {
        assertThat(result(FixId.NO_FREEZE, -1, t -> t.pauses(2, 8000), t -> t.pauses(0, 3000)))
                .isEqualTo(FocusResult.DONE);
    }

    @Test
    void noFreezeIsBetterWhenTheLongestGapIsThirtyPercentShorter() {
        UnaryOperator<Takes> before = t -> t.pauses(2, 8000);

        // 30% of 8000 is 2400, so 5600 is exactly 30% shorter
        assertThat(result(FixId.NO_FREEZE, -1, before, t -> t.pauses(1, 5600))).isEqualTo(FocusResult.BETTER);
        assertThat(result(FixId.NO_FREEZE, -1, before, t -> t.pauses(1, 5601))).isEqualTo(FocusResult.NOT_YET);
        assertThat(result(FixId.NO_FREEZE, -1, before, t -> t.pauses(1, 8000))).isEqualTo(FocusResult.NOT_YET);
    }

    @Test
    void noFreezeShowsTheLongestGapBeforeAndAfter() {
        FocusCheckResult r = check(FixId.NO_FREEZE, -1, t -> t.pauses(2, 8000), t -> t.pauses(1, 5600));

        assertThat(r.evidence()).isEqualTo(new FocusEvidence(FocusMeasure.LONGEST_PAUSE_MS, 8000, 5600));
    }

    // ---- P7 filler_cluster: no cluster and rate down 30% = Done, either = Better
    // ------------------------------------------

    @Test
    void fillerClusterIsDoneWithNoClusterAndTheRateDownThirtyPercent() {
        // before: 10 fillers in a 60 s take = 10 a minute. 30% down is 7 a minute.
        UnaryOperator<Takes> before = t -> t.durationMs(60_000).fillers(10, 2);

        assertThat(result(
                        FixId.FILLER_CLUSTER,
                        -1,
                        before,
                        t -> t.durationMs(60_000).fillers(7, 0)))
                .isEqualTo(FocusResult.DONE);
    }

    @Test
    void fillerClusterIsBetterWhenOnlyTheClusterIsGone() {
        UnaryOperator<Takes> before = t -> t.durationMs(60_000).fillers(10, 2);

        // 8 a minute is not down 30%, but there is no cluster any more
        assertThat(result(
                        FixId.FILLER_CLUSTER,
                        -1,
                        before,
                        t -> t.durationMs(60_000).fillers(8, 0)))
                .isEqualTo(FocusResult.BETTER);
    }

    @Test
    void fillerClusterIsBetterWhenOnlyTheRateIsDown() {
        UnaryOperator<Takes> before = t -> t.durationMs(60_000).fillers(10, 2);

        // 5 a minute is down 50%, but a cluster is still there
        assertThat(result(
                        FixId.FILLER_CLUSTER,
                        -1,
                        before,
                        t -> t.durationMs(60_000).fillers(5, 1)))
                .isEqualTo(FocusResult.BETTER);
    }

    @Test
    void fillerClusterIsNotYetWhenNeitherChanged() {
        UnaryOperator<Takes> before = t -> t.durationMs(60_000).fillers(10, 2);

        assertThat(result(
                        FixId.FILLER_CLUSTER,
                        -1,
                        before,
                        t -> t.durationMs(60_000).fillers(9, 1)))
                .isEqualTo(FocusResult.NOT_YET);
    }

    @Test
    void fillerClusterComparesRatesNotCounts() {
        UnaryOperator<Takes> before = t -> t.durationMs(60_000).fillers(10, 2);

        // 3 fillers in a 30 s take is 6 a minute, which is down 40%
        assertThat(result(
                        FixId.FILLER_CLUSTER,
                        -1,
                        before,
                        t -> t.durationMs(30_000).windows(140, 140, 140).fillers(3, 0)))
                .isEqualTo(FocusResult.DONE);
    }

    @Test
    void fillerClusterShowsTheRatesBeforeAndAfter() {
        FocusCheckResult r = check(
                FixId.FILLER_CLUSTER,
                -1,
                t -> t.durationMs(60_000).fillers(10, 2),
                t -> t.durationMs(60_000).fillers(7, 0));

        assertThat(r.evidence()).isEqualTo(new FocusEvidence(FocusMeasure.FILLERS_PER_MINUTE, 10, 7));
    }

    // ---- P8 start_sooner: first word within 2 s
    // -------------------------------------------------------------------------------

    @Test
    void startSoonerIsDoneWhenTheFirstWordComesWithinTwoSeconds() {
        UnaryOperator<Takes> before = t -> t.firstWordMs(5000);

        assertThat(result(FixId.START_SOONER, -1, before, t -> t.firstWordMs(2000)))
                .isEqualTo(FocusResult.DONE);
        assertThat(result(FixId.START_SOONER, -1, before, t -> t.firstWordMs(2001)))
                .isEqualTo(FocusResult.NOT_YET);
        assertThat(result(FixId.START_SOONER, -1, before, t -> t.firstWordMs(3000)))
                .isEqualTo(FocusResult.NOT_YET);
    }

    // ---- P10 split_sentence: longest 30 or less
    // ------------------------------------------------------------------------------

    @Test
    void splitSentenceIsDoneWhenTheLongestSentenceIsThirtyWordsOrLess() {
        UnaryOperator<Takes> before = t -> t.longestSentence(50, true);

        assertThat(result(FixId.SPLIT_SENTENCE, -1, before, t -> t.longestSentence(30, false)))
                .isEqualTo(FocusResult.DONE);
        assertThat(result(FixId.SPLIT_SENTENCE, -1, before, t -> t.longestSentence(31, false)))
                .isEqualTo(FocusResult.NOT_YET);
    }

    // ---- P11 steady_pace: 80% of windows in the band
    // --------------------------------------------------------------------------

    @Test
    void steadyPaceIsDoneWhenEightyPercentOfWindowsAreInTheBand() {
        UnaryOperator<Takes> before = t -> t.durationMs(60_000).windows(140, 140, 140, 140, 60, 60);

        // 5 of 6 windows in band is 83%
        assertThat(result(
                        FixId.STEADY_PACE, -1, before, t -> t.durationMs(60_000).windows(140, 140, 140, 140, 140, 60)))
                .isEqualTo(FocusResult.DONE);
        // 4 of 6 is 66%
        assertThat(result(
                        FixId.STEADY_PACE, -1, before, t -> t.durationMs(60_000).windows(140, 140, 140, 140, 60, 60)))
                .isEqualTo(FocusResult.NOT_YET);
        // 4 of 5 is exactly 80%
        assertThat(result(
                        FixId.STEADY_PACE, -1, before, t -> t.durationMs(50_000).windows(140, 140, 140, 140, 60)))
                .isEqualTo(FocusResult.DONE);
    }

    @Test
    void steadyPaceUsesTheBandOfTheTakeBeingChecked() {
        UnaryOperator<Takes> before = t -> t.durationMs(60_000).windows(140, 140, 140, 140, 60, 60);

        // with the person's own band 100..150, five of six windows are inside
        assertThat(result(
                        FixId.STEADY_PACE,
                        -1,
                        before,
                        t -> t.band(100, 150).durationMs(60_000).windows(120, 120, 120, 120, 160, 120)))
                .isEqualTo(FocusResult.DONE);
    }

    @Test
    void steadyPaceShowsTheShareOfWindowsInTheBandBeforeAndAfter() {
        FocusCheckResult r = check(
                FixId.STEADY_PACE,
                -1,
                t -> t.durationMs(60_000).windows(140, 140, 140, 140, 60, 60),
                t -> t.durationMs(60_000).windows(140, 140, 140, 140, 140, 60));

        // 4 of 6 = 66.7 -> 67 and 5 of 6 = 83.3 -> 83
        assertThat(r.evidence()).isEqualTo(new FocusEvidence(FocusMeasure.WINDOWS_IN_BAND_PERCENT, 67, 83));
    }

    // ---- P12 the Language level up by 1
    // ----------------------------------------------------------------------------------------

    static Stream<Arguments> languageFixes() {
        return Stream.of(
                Arguments.of(
                        FixId.SAY_IT_PLAINLY,
                        (UnaryOperator<Takes>) t -> t.language(2, 4, 4),
                        (UnaryOperator<Takes>) t -> t.language(3, 4, 4),
                        (UnaryOperator<Takes>) t -> t.language(2, 4, 4)),
                Arguments.of(
                        FixId.SAY_IT_SHORTER,
                        (UnaryOperator<Takes>) t -> t.language(4, 2, 4),
                        (UnaryOperator<Takes>) t -> t.language(4, 3, 4),
                        (UnaryOperator<Takes>) t -> t.language(4, 2, 4)),
                Arguments.of(
                        FixId.MORE_STANDARD,
                        (UnaryOperator<Takes>) t -> t.language(4, 4, 2),
                        (UnaryOperator<Takes>) t -> t.language(4, 4, 3),
                        (UnaryOperator<Takes>) t -> t.language(4, 4, 2)));
    }

    @ParameterizedTest(name = "{0}: up by 1 is Done, no change is Not yet")
    @MethodSource("languageFixes")
    void aLanguageFixIsDoneWhenItsLevelRoseByOne(
            FixId fix, UnaryOperator<Takes> before, UnaryOperator<Takes> risen, UnaryOperator<Takes> same) {
        assertThat(result(fix, -1, before, risen)).isEqualTo(FocusResult.DONE);
        assertThat(result(fix, -1, before, same)).isEqualTo(FocusResult.NOT_YET);
    }

    @Test
    void aLanguageLevelThatFellIsNotYet() {
        assertThat(result(FixId.SAY_IT_PLAINLY, -1, t -> t.language(3, 4, 4), t -> t.language(2, 4, 4)))
                .isEqualTo(FocusResult.NOT_YET);
    }

    @Test
    void aPolishAtTheTopLevelIsDoneWhenItStaysAtTheTop() {
        // level 4 cannot rise by 1, so staying at 4 is Done
        assertThat(result(FixId.SAY_IT_PLAINLY, -1, t -> t, t -> t)).isEqualTo(FocusResult.DONE);
    }

    @Test
    void aLanguageFixShowsTheLevelBeforeAndAfter() {
        FocusCheckResult r = check(FixId.SAY_IT_SHORTER, -1, t -> t.language(4, 2, 4), t -> t.language(4, 3, 4));

        assertThat(r.evidence()).isEqualTo(new FocusEvidence(FocusMeasure.LANGUAGE_LEVEL, 2, 3));
    }

    // ---- the Done lines read the settings
    // -------------------------------------------------------------------------------------

    @Test
    void noFreezeAndFillerClusterDoneLinesFollowTheSettings() {
        FixSettings stricter = FixSettingsFixture.withPauseAndClusterTriggers(2, 2);

        // one pause is under the trigger of 2, so no_freeze is Done with the stricter settings and not with the launch
        // ones
        PreviousTake noFreeze = new PreviousTake(
                new FixChoice(FixId.NO_FREEZE, -1, false, false),
                Takes.strong().pauses(3, 8000).build());
        assertThat(FocusCheck.check(noFreeze, Takes.strong().pauses(1, 5000).build(), stricter)
                        .result())
                .isEqualTo(FocusResult.DONE);
        assertThat(FocusCheck.check(noFreeze, Takes.strong().pauses(1, 5000).build(), SETTINGS)
                        .result())
                .isNotEqualTo(FocusResult.DONE);

        PreviousTake filler = new PreviousTake(
                new FixChoice(FixId.FILLER_CLUSTER, -1, false, false),
                Takes.strong().durationMs(60_000).fillers(10, 2).build());
        TakeFacts oneClusterRateDown =
                Takes.strong().durationMs(60_000).fillers(7, 1).build();
        assertThat(FocusCheck.check(filler, oneClusterRateDown, stricter).result())
                .isEqualTo(FocusResult.DONE);
        assertThat(FocusCheck.check(filler, oneClusterRateDown, SETTINGS).result())
                .isEqualTo(FocusResult.BETTER);
    }

    // ---- every fix has a rule
    // -------------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(FixId.class)
    void everyFixInTheLibraryIsDoneWhenTheTakeStaysStrong(FixId fix) {
        // a strong take after a strong take: nothing is left to fix, so every fix reads Done
        FocusCheckResult r = check(fix, 1, t -> t, t -> t);

        assertThat(r.fixId()).isEqualTo(fix);
        assertThat(r.result()).isEqualTo(FocusResult.DONE);
        assertThat(r.evidence()).isNotNull();
    }
}
