package app.stageminute.analysis.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 03 F-032: which fix the code picks for a take, with no earlier take. One row per row of the launch table, each at
 * its line (a fact exactly on the line, then one step over), then the order in which triggers are checked. Expected
 * values are read from the F-032 table, not from the code.
 */
class FixLibraryPickTest {

    static final FixSettings SETTINGS = FixSettings.LAUNCH_DEFAULT;

    private static Arguments row(String name, UnaryOperator<Takes> change, FixId expected) {
        return Arguments.of(name, change, expected);
    }

    private static FixChoice pick(UnaryOperator<Takes> change) {
        return FixLibrary.firstFix(change.apply(Takes.strong()).build(), SETTINGS);
    }

    // ---- P0 speak_longer: under 40 words or 20 s of speech ----------------------------------------------------------

    @Test
    void underFortyWordsAsksForALongerAnswer() {
        assertThat(pick(t -> t.words(39)).id()).isEqualTo(FixId.SPEAK_LONGER);
    }

    @Test
    void underTwentySecondsOfSpeechAsksForALongerAnswer() {
        assertThat(pick(t -> t.speechMs(19_999)).id()).isEqualTo(FixId.SPEAK_LONGER);
    }

    @Test
    void fortyWordsAndTwentySecondsOfSpeechAreEnough() {
        assertThat(pick(t -> t.words(40).speechMs(20_000)).id()).isNotEqualTo(FixId.SPEAK_LONGER);
    }

    // ---- P1 answer_question: relevance level 1 ----------------------------------------------------------------------

    @Test
    void anOffTopicTakeIsToldToAnswerTheQuestion() {
        assertThat(pick(t -> t.relevance(1)).id()).isEqualTo(FixId.ANSWER_QUESTION);
    }

    @Test
    void relevanceLevelTwoIsNotOffTopic() {
        assertThat(pick(t -> t.relevance(2)).id()).isNotEqualTo(FixId.ANSWER_QUESTION);
    }

    // ---- P2 to P5: a missing step, named by style
    // ---------------------------------------------------------------------

    static Stream<Arguments> missingSteps() {
        return Stream.of(
                row("PREP point", t -> t.step(0, StepStatus.MISSING), FixId.OPEN_POINT),
                row("STAR situation", t -> t.star().step(0, StepStatus.MISSING), FixId.SET_SCENE),
                row("PREP closing", t -> t.step(3, StepStatus.MISSING), FixId.ADD_CLOSING),
                row("STAR result", t -> t.star().step(3, StepStatus.MISSING), FixId.SAY_RESULT),
                row("PREP example", t -> t.step(2, StepStatus.MISSING), FixId.ADD_EXAMPLE),
                row("STAR action", t -> t.star().step(2, StepStatus.MISSING), FixId.NAME_ACTION),
                row("PREP reason", t -> t.step(1, StepStatus.MISSING), FixId.ADD_REASON),
                row("STAR task", t -> t.star().step(1, StepStatus.MISSING), FixId.NAME_TASK));
    }

    @ParameterizedTest(name = "{0} missing -> {2}")
    @MethodSource("missingSteps")
    void aMissingStepGetsTheFixNamedForItsStyle(String name, UnaryOperator<Takes> change, FixId expected) {
        FixChoice choice = pick(change);

        assertThat(choice.id()).isEqualTo(expected);
        assertThat(choice.carriedOver()).isFalse();
        assertThat(choice.nothingMajor()).isFalse();
    }

    @Test
    void aMissingStepRemembersWhichStepItWas() {
        assertThat(pick(t -> t.step(2, StepStatus.MISSING)).target()).isEqualTo(2);
    }

    @Test
    void aWeakStepIsNotMissing() {
        // weak steps are handled at P9 (strengthen_step), not by the "missing step" rows
        assertThat(pick(t -> t.step(0, StepStatus.WEAK)).id()).isEqualTo(FixId.STRENGTHEN_STEP);
    }

    // ---- P6 no_freeze: a mid-answer gap of 5 s or more
    // ---------------------------------------------------------------

    @Test
    void oneMidAnswerPauseOfFiveSecondsAsksForNoFreezing() {
        assertThat(pick(t -> t.pauses(1, 5000)).id()).isEqualTo(FixId.NO_FREEZE);
    }

    @Test
    void noMidAnswerPauseMeansNoFreezeFix() {
        // a 3 s pause was recorded (1.2 s or more) but it is not a mid-answer pause of 5 s, so the count stays 0
        assertThat(pick(t -> t.pauses(0, 3000)).id()).isNotEqualTo(FixId.NO_FREEZE);
    }

    // ---- P7 filler_cluster: 3 or more confirmed fillers in 10 s, or 8 or more a minute ------------------------------

    @Test
    void aFillerClusterAsksForFewerFillers() {
        assertThat(pick(t -> t.fillers(3, 1)).id()).isEqualTo(FixId.FILLER_CLUSTER);
    }

    @Test
    void eightFillersInAMinuteAsksForFewerFillersWithoutACluster() {
        // 8 in a 60 s take is exactly 8 a minute
        assertThat(pick(t -> t.durationMs(60_000).fillers(8, 0)).id()).isEqualTo(FixId.FILLER_CLUSTER);
    }

    @Test
    void sevenFillersInAMinuteWithoutAClusterIsFine() {
        assertThat(pick(t -> t.durationMs(60_000).fillers(7, 0)).id()).isNotEqualTo(FixId.FILLER_CLUSTER);
    }

    @Test
    void theFillerRateIsTheRateNotTheCount() {
        // 4 fillers in a 28 s take is 8.57 a minute; 4 in a 60 s take is 4 a minute
        assertThat(pick(t -> t.durationMs(28_000).windows(140, 140).fillers(4, 0))
                        .id())
                .isEqualTo(FixId.FILLER_CLUSTER);
        assertThat(pick(t -> t.durationMs(60_000).fillers(4, 0)).id()).isNotEqualTo(FixId.FILLER_CLUSTER);
    }

    @Test
    void theClusterAndPauseTriggersComeFromTheSettings() {
        TakeFacts onePauseOneCluster =
                Takes.strong().pauses(1, 5000).fillers(3, 1).build();

        // with the launch values both fire (the pause first); asking for 2 pauses and 2 clusters turns both off
        assertThat(FixLibrary.firstFix(onePauseOneCluster, SETTINGS).id()).isEqualTo(FixId.NO_FREEZE);
        FixSettings stricter = FixSettingsFixture.withPauseAndClusterTriggers(2, 2);
        assertThat(FixLibrary.firstFix(onePauseOneCluster, stricter).id())
                .isNotIn(FixId.NO_FREEZE, FixId.FILLER_CLUSTER);
    }

    // ---- P8 start_sooner: the first word after 4 s
    // --------------------------------------------------------------------

    @Test
    void aFirstWordAfterFourSecondsAsksToStartSooner() {
        assertThat(pick(t -> t.firstWordMs(4001)).id()).isEqualTo(FixId.START_SOONER);
    }

    @Test
    void aFirstWordAtExactlyFourSecondsIsNotAfterFour() {
        assertThat(pick(t -> t.firstWordMs(4000)).id()).isNotEqualTo(FixId.START_SOONER);
    }

    // ---- P9 strengthen_step: a step weak ----------------------------------------------------------------------------

    @Test
    void aWeakStepAsksToStrengthenIt() {
        FixChoice choice = pick(t -> t.step(1, StepStatus.WEAK));

        assertThat(choice.id()).isEqualTo(FixId.STRENGTHEN_STEP);
        assertThat(choice.target()).isEqualTo(1);
    }

    @Test
    void withTwoWeakStepsTheFirstInTheStylesOrderIsTaken() {
        assertThat(pick(t -> t.step(3, StepStatus.WEAK).step(1, StepStatus.WEAK))
                        .target())
                .isEqualTo(1);
    }

    // ---- P10 split_sentence: a sentence over 40 words, labelled run-on ----------------------------------------------

    @Test
    void aLongSentenceTheCallLabelledRunOnIsToldToSplit() {
        assertThat(pick(t -> t.longestSentence(41, true)).id()).isEqualTo(FixId.SPLIT_SENTENCE);
    }

    @Test
    void aLongSentenceWithoutTheRunOnLabelIsLeftAlone() {
        assertThat(pick(t -> t.longestSentence(41, false)).id()).isNotEqualTo(FixId.SPLIT_SENTENCE);
    }

    @Test
    void aRunOnLabelOnAFortyWordSentenceIsNotOverForty() {
        assertThat(pick(t -> t.longestSentence(40, true)).id()).isNotEqualTo(FixId.SPLIT_SENTENCE);
    }

    // ---- P11 steady_pace: 20 s or more outside the band
    // ----------------------------------------------------------------

    @Test
    void twoWindowsOutsideTheBandAreTwentySeconds() {
        // 60 s take, 6 windows of 10 s; windows 5 and 6 at 60 wpm are under 85
        assertThat(pick(t -> t.durationMs(60_000).windows(140, 140, 140, 140, 60, 60))
                        .id())
                .isEqualTo(FixId.STEADY_PACE);
    }

    @Test
    void oneWindowOutsideTheBandIsTenSeconds() {
        assertThat(pick(t -> t.durationMs(60_000).windows(140, 140, 140, 140, 140, 60))
                        .id())
                .isNotEqualTo(FixId.STEADY_PACE);
    }

    @Test
    void aShortLastWindowCountsForItsOwnLength() {
        // 55 s: five 10 s windows and a 5 s window. Windows 5 and 6 outside = 10 + 5 = 15 s (not 20)
        assertThat(pick(t -> t.durationMs(55_000).windows(140, 140, 140, 140, 60, 60))
                        .id())
                .isNotEqualTo(FixId.STEADY_PACE);
        // windows 4, 5 and 6 outside = 10 + 10 + 5 = 25 s
        assertThat(pick(t -> t.durationMs(55_000).windows(140, 140, 140, 60, 60, 60))
                        .id())
                .isEqualTo(FixId.STEADY_PACE);
    }

    @Test
    void thePersonalBandDecidesWhatIsOutside() {
        // band 100..150: windows at 160 are outside, though inside the launch band 85..240
        assertThat(pick(t -> t.band(100, 150).durationMs(60_000).windows(140, 140, 140, 140, 160, 160))
                        .id())
                .isEqualTo(FixId.STEADY_PACE);
    }

    @Test
    void theBandEdgesCountAsInside() {
        assertThat(pick(t -> t.durationMs(60_000).windows(85, 85, 240, 240, 85, 240))
                        .id())
                .isNotEqualTo(FixId.STEADY_PACE);
    }

    // ---- P12 the lowest Language level at 2 or less -----------------------------------------------------------------

    static Stream<Arguments> languageRows() {
        return Stream.of(
                row("clarity 2", t -> t.language(2, 4, 4), FixId.SAY_IT_PLAINLY),
                row("conciseness 2", t -> t.language(4, 2, 4), FixId.SAY_IT_SHORTER),
                row("accuracy 2", t -> t.language(4, 4, 2), FixId.MORE_STANDARD),
                row("clarity 1", t -> t.language(1, 3, 3), FixId.SAY_IT_PLAINLY),
                row("accuracy 1 is lower than clarity 2", t -> t.language(2, 4, 1), FixId.MORE_STANDARD),
                row("a three-way tie goes clarity first", t -> t.language(2, 2, 2), FixId.SAY_IT_PLAINLY),
                row(
                        "a tie of conciseness and accuracy goes to conciseness",
                        t -> t.language(4, 2, 2),
                        FixId.SAY_IT_SHORTER));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("languageRows")
    void theLowestLanguageLevelAtTwoOrLessGetsItsFix(String name, UnaryOperator<Takes> change, FixId expected) {
        FixChoice choice = pick(change);

        assertThat(choice.id()).isEqualTo(expected);
        assertThat(choice.nothingMajor()).isFalse();
    }

    // ---- a strong take: one polish, "Nothing major to fix"
    // -------------------------------------------------------------

    static Stream<Arguments> polishRows() {
        return Stream.of(
                row("all 4: the first criterion", t -> t, FixId.SAY_IT_PLAINLY),
                row("lowest is conciseness 3", t -> t.language(4, 3, 4), FixId.SAY_IT_SHORTER),
                row("lowest is accuracy 3", t -> t.language(4, 4, 3), FixId.MORE_STANDARD));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("polishRows")
    void aStrongTakeGetsOnePolishMarkedNothingMajor(String name, UnaryOperator<Takes> change, FixId expected) {
        FixChoice choice = pick(change);

        assertThat(choice.id()).isEqualTo(expected);
        assertThat(choice.nothingMajor()).isTrue();
    }

    // ---- wrap_up_in_time: the take hit the cap with the closing missing
    // --------------------------------------------------

    @Test
    void hittingTheCapWithoutAClosingAsksToWrapUpInTime() {
        assertThat(pick(t -> t.step(3, StepStatus.MISSING).hitCap()).id()).isEqualTo(FixId.WRAP_UP_IN_TIME);
    }

    @Test
    void aStarTakeThatHitTheCapWithoutAResultAlsoWrapsUpInTime() {
        assertThat(pick(t -> t.star().step(3, StepStatus.MISSING).hitCap()).id())
                .isEqualTo(FixId.WRAP_UP_IN_TIME);
    }

    @Test
    void aTakeThatEndedNaturallyWithoutAClosingGetsAddClosing() {
        assertThat(pick(t -> t.step(3, StepStatus.MISSING)).id()).isEqualTo(FixId.ADD_CLOSING);
    }

    @Test
    void hittingTheCapWithAClosingNeedsNoWrapUp() {
        assertThat(pick(Takes::hitCap).id()).isNotEqualTo(FixId.WRAP_UP_IN_TIME);
    }

    @Test
    void wrapUpInTimeOutranksTheLowerFixesButNotThePointOrTheQuestion() {
        // a missing example (P4) loses to wrap_up_in_time (P3)
        assertThat(pick(t -> t.step(3, StepStatus.MISSING)
                                .step(2, StepStatus.MISSING)
                                .hitCap())
                        .id())
                .isEqualTo(FixId.WRAP_UP_IN_TIME);
        // a missing point (P2) and an off-topic take (P1) come first
        assertThat(pick(t -> t.step(3, StepStatus.MISSING)
                                .step(0, StepStatus.MISSING)
                                .hitCap())
                        .id())
                .isEqualTo(FixId.OPEN_POINT);
        assertThat(pick(t -> t.step(3, StepStatus.MISSING).relevance(1).hitCap())
                        .id())
                .isEqualTo(FixId.ANSWER_QUESTION);
    }

    // ---- the order: the first trigger that fires wins
    // ------------------------------------------------------------------------

    static Stream<Arguments> order() {
        return Stream.of(
                row("too short beats off topic", t -> t.words(10).relevance(1), FixId.SPEAK_LONGER),
                row(
                        "off topic beats a missing point",
                        t -> t.relevance(1).step(0, StepStatus.MISSING),
                        FixId.ANSWER_QUESTION),
                row(
                        "point beats closing",
                        t -> t.step(0, StepStatus.MISSING).step(3, StepStatus.MISSING),
                        FixId.OPEN_POINT),
                row(
                        "closing beats example",
                        t -> t.step(3, StepStatus.MISSING).step(2, StepStatus.MISSING),
                        FixId.ADD_CLOSING),
                row(
                        "example beats reason",
                        t -> t.step(2, StepStatus.MISSING).step(1, StepStatus.MISSING),
                        FixId.ADD_EXAMPLE),
                row("reason beats a freeze", t -> t.step(1, StepStatus.MISSING).pauses(1, 6000), FixId.ADD_REASON),
                row("a freeze beats fillers", t -> t.pauses(1, 6000).fillers(3, 1), FixId.NO_FREEZE),
                row("fillers beat a late start", t -> t.fillers(3, 1).firstWordMs(5000), FixId.FILLER_CLUSTER),
                row(
                        "a late start beats a weak step",
                        t -> t.firstWordMs(5000).step(0, StepStatus.WEAK),
                        FixId.START_SOONER),
                row(
                        "a weak step beats a run-on",
                        t -> t.step(0, StepStatus.WEAK).longestSentence(50, true),
                        FixId.STRENGTHEN_STEP),
                row(
                        "a run-on beats the pace",
                        t -> t.longestSentence(50, true).durationMs(60_000).windows(140, 140, 140, 140, 60, 60),
                        FixId.SPLIT_SENTENCE),
                row(
                        "the pace beats a weak Language level",
                        t -> t.durationMs(60_000)
                                .windows(140, 140, 140, 140, 60, 60)
                                .language(2, 4, 4),
                        FixId.STEADY_PACE));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("order")
    void theFirstTriggerThatFiresWins(String name, UnaryOperator<Takes> change, FixId expected) {
        assertThat(pick(change).id()).isEqualTo(expected);
    }

    @Test
    void aFreshPickIsNeverCarriedOver() {
        assertThat(pick(t -> t.step(0, StepStatus.MISSING)).carriedOver()).isFalse();
    }

    // ---- settings
    // ------------------------------------------------------------------------------------------------------------

    @Test
    void settingsHoldTheRequirementValues() {
        FixSettings s = FixSettings.LAUNCH_DEFAULT;

        assertThat(s.speakLongerMinWords()).isEqualTo(40);
        assertThat(s.speakLongerMinSpeechMs()).isEqualTo(20_000);
        assertThat(s.speakLongerDoneMinSpeechMs()).isEqualTo(25_000);
        assertThat(s.offTopicLevel()).isEqualTo(1);
        assertThat(s.relevanceDoneLevel()).isEqualTo(3);
        assertThat(s.noFreezeMinPauses()).isEqualTo(1);
        assertThat(s.pauseShorterPercent()).isEqualTo(30);
        assertThat(s.fillersPerMinuteTrigger()).isEqualTo(8);
        assertThat(s.fillerClustersTrigger()).isEqualTo(1);
        assertThat(s.fillerRateDropPercent()).isEqualTo(30);
        assertThat(s.startSoonerOverMs()).isEqualTo(4000);
        assertThat(s.startSoonerDoneWithinMs()).isEqualTo(2000);
        assertThat(s.splitSentenceOverWords()).isEqualTo(40);
        assertThat(s.splitSentenceDoneMaxWords()).isEqualTo(30);
        assertThat(s.steadyPaceOutsideMs()).isEqualTo(20_000);
        assertThat(s.steadyPaceDoneInBandPercent()).isEqualTo(80);
        assertThat(s.languageFixMaxLevel()).isEqualTo(2);
        assertThat(s.languageDoneRise()).isEqualTo(1);
    }

    @Test
    void thePaceWindowLengthAgreesWithMetricsV2() {
        assertThat(FixSettings.LAUNCH_DEFAULT.windowMs()).isEqualTo(MetricsSettings.LAUNCH_DEFAULT.windowMs());
    }

    /** The launch settings with four values replaced. */
    private static FixSettings settings(int minWords, int minSpeechMs, int doneSpeechMs, int inBandPercent) {
        FixSettings d = FixSettings.LAUNCH_DEFAULT;
        return new FixSettings(
                minWords,
                minSpeechMs,
                doneSpeechMs,
                d.offTopicLevel(),
                d.relevanceDoneLevel(),
                d.noFreezeMinPauses(),
                d.pauseShorterPercent(),
                d.fillersPerMinuteTrigger(),
                d.fillerClustersTrigger(),
                d.fillerRateDropPercent(),
                d.startSoonerOverMs(),
                d.startSoonerDoneWithinMs(),
                d.splitSentenceOverWords(),
                d.splitSentenceDoneMaxWords(),
                d.steadyPaceOutsideMs(),
                inBandPercent,
                d.windowMs(),
                d.languageFixMaxLevel(),
                d.languageDoneRise());
    }

    @Test
    void badSettingsAreRejected() {
        assertThatThrownBy(() -> settings(0, 20_000, 25_000, 80)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(40, 20_000, 25_000, 101)).isInstanceOf(IllegalArgumentException.class);
        // "Done" must ask for at least as much speech as the trigger
        assertThatThrownBy(() -> settings(40, 20_000, 19_000, 80)).isInstanceOf(IllegalArgumentException.class);
        assertThat(settings(40, 20_000, 25_000, 80)).isEqualTo(FixSettings.LAUNCH_DEFAULT);
    }
}
