package app.stageminute.analysis.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The take map's moments (glossary: up to 3 marked places, "fillers, pause, how you ended"; 03 F-027 "pauses ...
 * shown as a moment"; F-026 `moments[]` max 3). The requirements give no more rules than that, so the choice among
 * more than three is a reading, listed in step3/CONFLICTS.md C-39. Expected values are worked by hand.
 */
class MomentsTest {

    static final MetricsSettings METRICS = MetricsSettings.LAUNCH_DEFAULT;
    static final MomentSettings SETTINGS = MomentSettings.LAUNCH_DEFAULT;

    /** A take with words; each entry is {start, end} in ms. One sentence per {@code sentenceStarts} index. */
    private static Transcript transcript(int[][] times, int... sentenceStarts) {
        List<TimedWord> words = new ArrayList<>();
        for (int[] t : times) {
            words.add(new TimedWord("w", t[0], t[1], 0.9));
        }
        List<SentenceSpan> sentences = new ArrayList<>();
        for (int i = 0; i < sentenceStarts.length; i++) {
            int last = i + 1 < sentenceStarts.length ? sentenceStarts[i + 1] - 1 : times.length - 1;
            sentences.add(new SentenceSpan(sentenceStarts[i], last));
        }
        return new Transcript(words, sentences, 60_000);
    }

    /** Words 300 ms long with a gap before each: gaps[i] is the silence before word i. */
    private static int[][] gapped(int... gaps) {
        int[][] times = new int[gaps.length][];
        int end = 0;
        for (int i = 0; i < gaps.length; i++) {
            int start = end + gaps[i];
            end = start + 300;
            times[i] = new int[] {start, end};
        }
        return times;
    }

    private static TakeFacts take(boolean hitCap, boolean closingMissing) {
        Takes t = Takes.strong();
        if (hitCap) {
            t.hitCap();
        }
        if (closingMissing) {
            t.step(3, StepStatus.MISSING);
        }
        return t.build();
    }

    private static TakeMoments pick(Transcript transcript, TakeFacts take, Optional<FixId> fix) {
        return pick(transcript, List.of(), take, fix, SETTINGS);
    }

    private static TakeMoments pick(
            Transcript transcript,
            List<FillerCandidate> candidates,
            TakeFacts take,
            Optional<FixId> fix,
            MomentSettings s) {
        return pick(transcript, candidates, take, fix, s, Map.of());
    }

    private static TakeMoments pick(
            Transcript transcript,
            List<FillerCandidate> candidates,
            TakeFacts take,
            Optional<FixId> fix,
            MomentSettings s,
            Map<Integer, FillerLabel> labels) {
        FillerFacts fillers = FillerTally.of(candidates, labels, take.durationMs(), METRICS);
        return Moments.pick(transcript, candidates, fillers, take, fix, METRICS, s);
    }

    @Test
    void aSmoothTakeHasNoMoments() {
        Transcript smooth = transcript(gapped(0, 100, 100, 100), 0);

        assertThat(pick(smooth, take(false, false), Optional.empty()).moments()).isEmpty();
    }

    // ---- pauses
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void aRecordedPauseIsAMomentFromTheEndOfOneWordToTheStartOfTheNext() {
        // word 0: 0-300; word 1 starts 1500 ms later: 1800-2100. The pause is 300 to 1800.
        Transcript t = transcript(gapped(0, 1500), 0);

        assertThat(pick(t, take(false, false), Optional.empty()).moments())
                .containsExactly(new Moment(MomentKind.PAUSE, 300, 1800));
    }

    @Test
    void aGapUnderTheRecordLineIsNotAMoment() {
        Transcript t = transcript(gapped(0, 1199), 0);

        assertThat(pick(t, take(false, false), Optional.empty()).moments()).isEmpty();
    }

    @Test
    void silenceBeforeTheFirstWordIsNotAMoment() {
        Transcript t = transcript(gapped(6000, 100), 0);

        assertThat(pick(t, take(false, false), Optional.empty()).moments()).isEmpty();
    }

    // ---- filler clusters
    // --------------------------------------------------------------------------------------------------------

    @Test
    void aFillerClusterIsAMomentFromTheFirstFillerStartToTheLastFillerEnd() {
        // words 1, 2, 3 are the always-counted fillers at 1000-1300, 2000-2300, 3000-3300
        Transcript t = transcript(new int[][] {{0, 300}, {1000, 1300}, {2000, 2300}, {3000, 3300}, {4000, 4300}}, 0);
        List<FillerCandidate> candidates = List.of(
                new FillerCandidate(0, FillerKind.ALWAYS_COUNTED, 1, 1, 1000),
                new FillerCandidate(1, FillerKind.ALWAYS_COUNTED, 2, 2, 2000),
                new FillerCandidate(2, FillerKind.ALWAYS_COUNTED, 3, 3, 3000));

        TakeMoments moments = pick(t, candidates, take(false, false), Optional.empty(), SETTINGS);

        assertThat(moments.moments()).containsExactly(new Moment(MomentKind.FILLER_CLUSTER, 1000, 3300));
    }

    @Test
    void aClusterEndsAtTheEndOfItsLastFillerEvenWhenThatFillerIsSeveralWords() {
        // the last filler is "you know" (words 3 and 4): the cluster ends at the end of word 4 (4700), not word 3
        // (3300)
        Transcript t = transcript(new int[][] {{0, 300}, {1000, 1300}, {2000, 2300}, {3000, 3300}, {4400, 4700}}, 0);
        List<FillerCandidate> candidates = List.of(
                new FillerCandidate(0, FillerKind.ALWAYS_COUNTED, 1, 1, 1000),
                new FillerCandidate(1, FillerKind.ALWAYS_COUNTED, 2, 2, 2000),
                new FillerCandidate(2, FillerKind.IN_CONTEXT, 3, 4, 3000));

        TakeMoments moments =
                pick(t, candidates, take(false, false), Optional.empty(), SETTINGS, Map.of(2, FillerLabel.FILLER));

        assertThat(moments.moments()).containsExactly(new Moment(MomentKind.FILLER_CLUSTER, 1000, 4700));
    }

    @Test
    void fillersThatDoNotFormAClusterAreNotAMoment() {
        Transcript t = transcript(new int[][] {{0, 300}, {1000, 1300}, {2000, 2300}}, 0);
        List<FillerCandidate> candidates = List.of(
                new FillerCandidate(0, FillerKind.ALWAYS_COUNTED, 1, 1, 1000),
                new FillerCandidate(1, FillerKind.ALWAYS_COUNTED, 2, 2, 2000));

        assertThat(pick(t, candidates, take(false, false), Optional.empty(), SETTINGS)
                        .moments())
                .isEmpty();
    }

    // ---- how the take ended
    // ----------------------------------------------------------------------------------------------------

    @Test
    void aTakeThatHitTheCapHasAnEndingMomentOverItsLastSentence() {
        // two sentences: words 0-1 and words 2-3; the last runs from word 2 start (1800) to word 3 end (3100). The gaps
        // between words are under 1.2 s, so no pause is recorded.
        Transcript t = transcript(new int[][] {{0, 300}, {400, 700}, {1800, 2100}, {2800, 3100}}, 0, 2);

        assertThat(pick(t, take(true, false), Optional.empty()).moments())
                .containsExactly(new Moment(MomentKind.ENDING, 1800, 3100));
    }

    @Test
    void aTakeWithNoClosingHasAnEndingMoment() {
        Transcript t = transcript(new int[][] {{0, 300}, {400, 700}, {1800, 2100}, {2800, 3100}}, 0, 2);

        assertThat(pick(t, take(false, true), Optional.empty()).moments())
                .containsExactly(new Moment(MomentKind.ENDING, 1800, 3100));
    }

    @Test
    void aTakeThatEndedNaturallyWithAClosingHasNoEndingMoment() {
        Transcript t = transcript(new int[][] {{0, 300}, {400, 700}, {1800, 2100}, {2800, 3100}}, 0, 2);

        assertThat(pick(t, take(false, false), Optional.empty()).moments()).isEmpty();
    }

    // ---- at most three, in time order
    // ------------------------------------------------------------------------------------------------

    @Test
    void atMostThreeMomentsAreKeptTheLongestPausesFirstAndShownInTimeOrder() {
        // five pauses: gaps of 1300, 4000, 2000, 5000, 1500 ms. Longest three: 5000, 4000, 2000.
        Transcript t = transcript(gapped(0, 1300, 4000, 2000, 5000, 1500), 0);

        List<Moment> moments = pick(t, take(false, false), Optional.empty()).moments();

        // words: w0 0-300, w1 1600-1900, w2 5900-6200, w3 8200-8500, w4 13500-13800, w5 15300-15600, so the pauses are
        // 300-1600, 1900-5900, 6200-8200, 8500-13500 and 13800-15300
        assertThat(moments)
                .containsExactly(
                        new Moment(MomentKind.PAUSE, 1900, 5900),
                        new Moment(MomentKind.PAUSE, 6200, 8200),
                        new Moment(MomentKind.PAUSE, 8500, 13500));
    }

    @Test
    void oneOfEachKindComesBeforeASecondOfTheSameKind() {
        // three pauses, one cluster, an ending (the take hit the cap): the three moments are the longest pause, the
        // cluster and the ending, not three pauses
        int[][] times = {
            {0, 300}, {2000, 2300}, {4000, 4300}, {6000, 6300}, {7000, 7300}, {8000, 8300}, {9000, 9300}, {10000, 10300}
        };
        // gaps 1700 each between words 0-3 (pauses), words 4, 5, 6 are fillers 1000 ms apart (a cluster)
        Transcript t = transcript(times, 0, 7);
        List<FillerCandidate> candidates = List.of(
                new FillerCandidate(0, FillerKind.ALWAYS_COUNTED, 4, 4, 7000),
                new FillerCandidate(1, FillerKind.ALWAYS_COUNTED, 5, 5, 8000),
                new FillerCandidate(2, FillerKind.ALWAYS_COUNTED, 6, 6, 9000));

        List<Moment> moments = pick(t, candidates, take(true, false), Optional.empty(), SETTINGS)
                .moments();

        assertThat(moments)
                .extracting(Moment::kind)
                .containsExactly(MomentKind.PAUSE, MomentKind.FILLER_CLUSTER, MomentKind.ENDING);
    }

    // ---- the fix's moment
    // ----------------------------------------------------------------------------------------------------------------

    @Test
    void noFreezeHasTheLongestPauseAsItsMomentAndItIsKept() {
        Transcript t = transcript(gapped(0, 1300, 5000, 2000), 0);

        TakeMoments moments = pick(t, take(false, false), Optional.of(FixId.NO_FREEZE));

        assertThat(moments.fixMoment()).contains(new Moment(MomentKind.PAUSE, 1900, 6900));
        assertThat(moments.moments()).contains(new Moment(MomentKind.PAUSE, 1900, 6900));
    }

    @Test
    void theFixMomentIsKeptEvenWhenTheMapIsFull() {
        MomentSettings one =
                new MomentSettings(1, List.of(MomentKind.FILLER_CLUSTER, MomentKind.PAUSE, MomentKind.ENDING));
        Transcript t = transcript(gapped(0, 1300, 5000, 2000), 0);

        TakeMoments moments = pick(t, List.of(), take(false, false), Optional.of(FixId.NO_FREEZE), one);

        assertThat(moments.moments()).containsExactly(new Moment(MomentKind.PAUSE, 1900, 6900));
    }

    @Test
    void fillerClusterHasTheBiggestClusterAsItsMoment() {
        // two clusters: 3 fillers at 1000, 2000, 3000 and 4 fillers at 30 000, 31 000, 32 000, 33 000
        int[][] times = new int[10][];
        int[] starts = {0, 1000, 2000, 3000, 20_000, 30_000, 31_000, 32_000, 33_000, 40_000};
        for (int i = 0; i < starts.length; i++) {
            times[i] = new int[] {starts[i], starts[i] + 300};
        }
        Transcript t = transcript(times, 0);
        List<FillerCandidate> candidates = new ArrayList<>();
        int[] fillerWords = {1, 2, 3, 5, 6, 7, 8};
        for (int i = 0; i < fillerWords.length; i++) {
            candidates.add(new FillerCandidate(
                    i, FillerKind.ALWAYS_COUNTED, fillerWords[i], fillerWords[i], starts[fillerWords[i]]));
        }

        TakeMoments moments = pick(t, candidates, take(false, false), Optional.of(FixId.FILLER_CLUSTER), SETTINGS);

        assertThat(moments.fixMoment()).contains(new Moment(MomentKind.FILLER_CLUSTER, 30_000, 33_300));
    }

    @Test
    void theClosingFixesHaveTheEndingAsTheirMoment() {
        Transcript t = transcript(new int[][] {{0, 300}, {400, 700}, {1800, 2100}, {2800, 3100}}, 0, 2);
        Moment ending = new Moment(MomentKind.ENDING, 1800, 3100);

        for (FixId fix : List.of(FixId.ADD_CLOSING, FixId.SAY_RESULT, FixId.WRAP_UP_IN_TIME)) {
            assertThat(pick(t, take(true, true), Optional.of(fix)).fixMoment()).contains(ending);
        }
    }

    @Test
    void aFixWithNoMarkedPlaceHasNoFixMoment() {
        Transcript t = transcript(gapped(0, 1300), 0);

        assertThat(pick(t, take(false, false), Optional.of(FixId.START_SOONER)).fixMoment())
                .isEmpty();
        assertThat(pick(t, take(false, false), Optional.empty()).fixMoment()).isEmpty();
    }

    @Test
    void aFixWhosePlaceIsNotOnThisTakeHasNoFixMoment() {
        // filler_cluster was carried but this take has no cluster
        Transcript t = transcript(gapped(0, 100), 0);

        assertThat(pick(t, take(false, false), Optional.of(FixId.FILLER_CLUSTER))
                        .fixMoment())
                .isEmpty();
    }

    // ---- settings
    // --------------------------------------------------------------------------------------------------------------------------

    @Test
    void launchSettingsAreThreeMomentsAndTheOrderPauseClusterEnding() {
        assertThat(MomentSettings.LAUNCH_DEFAULT.maxMoments()).isEqualTo(3);
        assertThat(MomentSettings.LAUNCH_DEFAULT.kindOrder())
                .containsExactly(MomentKind.PAUSE, MomentKind.FILLER_CLUSTER, MomentKind.ENDING);
    }

    @Test
    void badMomentSettingsAreRejected() {
        assertThatThrownBy(() -> new MomentSettings(0, List.of(MomentKind.PAUSE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MomentSettings(3, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MomentSettings(3, List.of(MomentKind.PAUSE, MomentKind.PAUSE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMomentMustRunForwardInTime() {
        assertThatThrownBy(() -> new Moment(MomentKind.PAUSE, 500, 400)).isInstanceOf(IllegalArgumentException.class);
    }
}
