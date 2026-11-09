package app.stageminute.analysis.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 03 F-027, Metrics v2 from a transcript: first-word time, pauses (recorded from 1.2 s, counted at 5 s or more in the
 * middle of the answer), pace per 10 s window, words under 0.5 confidence, sentence-initial habit markers (So / Okay /
 * Right, at 3 or more), run-on sentences (over 40 words) and filler clusters (3 or more in 10 s). Every expected value
 * is worked by hand from the numbers in the requirement. Words are 300 ms long.
 */
class MetricsV2Test {

    static final MetricsSettings DEFAULT = MetricsSettings.LAUNCH_DEFAULT;
    static final int WORD_MS = 300;

    /** Words that follow each other with the given silence before each one (the first value is the start time). */
    private static List<TimedWord> wordsAfterGaps(int... gapsMs) {
        List<TimedWord> words = new ArrayList<>();
        int end = 0;
        for (int gap : gapsMs) {
            int start = end + gap;
            end = start + WORD_MS;
            words.add(new TimedWord("w", start, end, 0.9));
        }
        return words;
    }

    private static Transcript oneSentence(List<TimedWord> words, int durationMs) {
        return new Transcript(words, List.of(new SentenceSpan(0, words.size() - 1)), durationMs);
    }

    private static SpeechFacts compute(List<TimedWord> words, int durationMs) {
        return MetricsV2.compute(oneSentence(words, durationMs), DEFAULT);
    }

    // ---- first word and pauses ------------------------------------------------------------------------------

    @Test
    void firstWordTimeIsWhenTheFirstWordStarts() {
        SpeechFacts facts = compute(wordsAfterGaps(2500, 100, 100), 30_000);

        assertThat(facts.wordCount()).isEqualTo(3);
        assertThat(facts.firstWordMs()).isEqualTo(2500);
    }

    @Test
    void pausesAreRecordedFrom1200MsAndCountedFrom5000Ms() {
        // gaps: start 0, then 299 (no), 1199 (no), 1200 (recorded), 4999 (recorded, not counted), 5000 (counted)
        SpeechFacts facts = compute(wordsAfterGaps(0, 299, 1199, 1200, 4999, 5000), 60_000);

        assertThat(facts.pausesRecorded()).isEqualTo(3);
        assertThat(facts.midAnswerPauses()).isEqualTo(1);
        assertThat(facts.longestPauseMs()).isEqualTo(5000);
    }

    @Test
    void silenceBeforeTheFirstWordAndAfterTheLastIsNotAMidAnswerPause() {
        // 8 s of silence before the first word; the take runs to 40 s with silence after the last word
        SpeechFacts facts = compute(wordsAfterGaps(8000, 200, 200), 40_000);

        assertThat(facts.firstWordMs()).isEqualTo(8000);
        assertThat(facts.pausesRecorded()).isZero();
        assertThat(facts.midAnswerPauses()).isZero();
        assertThat(facts.longestPauseMs()).isZero();
    }

    // ---- pace windows ---------------------------------------------------------------------------------------

    private static List<TimedWord> wordsEvery(int stepMs, int count) {
        List<TimedWord> words = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            words.add(new TimedWord("w", i * stepMs, i * stepMs + WORD_MS, 0.9));
        }
        return words;
    }

    @Test
    void paceIsWordsInEachTenSecondWindowTimesSix() {
        // one word a second for 20 s: 10 words in each window = 60 wpm
        assertThat(compute(wordsEvery(1000, 20), 20_000).windowWpm()).containsExactly(60, 60);
        // 25 words in the first 10 s (one every 400 ms) and none after = 150 wpm, then 0
        assertThat(compute(wordsEvery(400, 25), 20_000).windowWpm()).containsExactly(150, 0);
    }

    @Test
    void aFinalWindowOfFiveSecondsOrMoreCountsAndIsScaledToItsLength() {
        // 25 s, one word a second: windows 0-10 s and 10-20 s have 10 words (60 wpm); the last 5 s has 5 words, which
        // is 60 words a minute over its own 5 s
        assertThat(compute(wordsEvery(1000, 25), 25_000).windowWpm()).containsExactly(60, 60, 60);
    }

    @Test
    void aFinalWindowUnderFiveSecondsIsLeftOut() {
        assertThat(compute(wordsEvery(1000, 25), 24_999).windowWpm()).containsExactly(60, 60);
    }

    // ---- low-confidence words -------------------------------------------------------------------------------

    @Test
    void wordsStrictlyUnderHalfConfidenceAreLowConfidence() {
        List<TimedWord> words = List.of(
                new TimedWord("a", 0, 300, 0.49),
                new TimedWord("b", 400, 700, 0.5),
                new TimedWord("c", 800, 1100, 0.9),
                new TimedWord("d", 1200, 1500, 0.1));

        assertThat(compute(words, 30_000).lowConfidenceWords()).isEqualTo(2);
    }

    // ---- habit markers and run-ons --------------------------------------------------------------------------

    private static Transcript sentences(String... sentenceTexts) {
        List<TimedWord> words = new ArrayList<>();
        List<SentenceSpan> spans = new ArrayList<>();
        int t = 0;
        for (String text : sentenceTexts) {
            int first = words.size();
            for (String token : text.split(" ", -1)) {
                words.add(new TimedWord(token, t, t + 100, 0.9));
                t += 150;
            }
            spans.add(new SentenceSpan(first, words.size() - 1));
        }
        return new Transcript(words, spans, t + 1000);
    }

    @Test
    void sentenceInitialSoOkayRightAreHabitMarkersAtThreeOrMore() {
        SpeechFacts three = MetricsV2.compute(
                sentences("So we shipped it", "Okay, the team agreed", "right. we moved on", "It worked"), DEFAULT);

        assertThat(three.habitMarkerCount()).isEqualTo(3);
        assertThat(three.habitMarkers()).isTrue();
    }

    @Test
    void twoHabitMarkersIsNotYetAHabit() {
        SpeechFacts two =
                MetricsV2.compute(sentences("So we shipped it", "Okay, the team agreed", "It worked"), DEFAULT);

        assertThat(two.habitMarkerCount()).isEqualTo(2);
        assertThat(two.habitMarkers()).isFalse();
    }

    @Test
    void onlyTheFirstWordOfASentenceCountsAndOnlyTheWholeWord() {
        // "so" in the middle, "Soon" and "Rights" are not markers
        SpeechFacts facts =
                MetricsV2.compute(sentences("We did it so well", "Soon after we left", "Rights matter here"), DEFAULT);

        assertThat(facts.habitMarkerCount()).isZero();
    }

    @ParameterizedTest(name = "a sentence of {0} words is a run-on candidate: {1}")
    @CsvSource({"40, false", "41, true", "12, false", "80, true"})
    void aSentenceOverFortyWordsIsARunOnCandidate(int length, boolean runOn) {
        String sentence = String.join(" ", java.util.Collections.nCopies(length, "word"));
        SpeechFacts facts = MetricsV2.compute(sentences("Short one here", sentence, "Another short"), DEFAULT);

        assertThat(facts.runOnSentences()).isEqualTo(runOn ? List.of(1) : List.of());
    }

    // ---- filler clusters ------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} gives {1} cluster(s)")
    @CsvSource(
            delimiter = '|',
            value = {
                // fewer than three is never a cluster
                "[]|0",
                "[0, 3000]|0",
                // three within 10 s is one cluster; the 10 s window is [first, first + 10 000)
                "[0, 1000, 2000]|1",
                "[0, 5000, 9999]|1",
                "[0, 5000, 10000]|0",
                // six close together are still one cluster
                "[0, 1000, 2000, 3000, 4000, 5000]|1",
                // two separate bursts
                "[0, 1000, 2000, 30000, 31000, 32000]|2",
                "[0, 1000, 2000, 3000, 12000, 13000, 14000]|2",
                // the order they are given in does not matter
                "[2000, 0, 1000]|1"
            })
    void threeOrMoreConfirmedFillersInTenSecondsIsOneCluster(String times, int expected) {
        List<Integer> startsMs = new ArrayList<>();
        for (String part : times.replace("[", "").replace("]", "").split(",", -1)) {
            if (!part.isBlank()) {
                startsMs.add(Integer.parseInt(part.trim()));
            }
        }

        assertThat(MetricsV2.fillerClusters(startsMs, DEFAULT)).isEqualTo(expected);
    }

    // ---- empty take, privacy and checks ---------------------------------------------------------------------

    @Test
    void aTakeWithNoWordsHasNoFacts() {
        SpeechFacts facts = MetricsV2.compute(new Transcript(List.of(), List.of(), 30_000), DEFAULT);

        assertThat(facts.wordCount()).isZero();
        assertThat(facts.firstWordMs()).isEqualTo(-1);
        assertThat(facts.windowWpm()).containsExactly(0, 0, 0);
    }

    @Test
    void aWordNeverPrintsItsText() {
        // Words are speech: they must not reach a log line by accident (root CLAUDE.md, privacy rules).
        TimedWord word = new TimedWord("secretname", 0, 300, 0.9);

        assertThat(word.toString()).doesNotContain("secretname");
        assertThat(oneSentence(List.of(word), 10_000).toString()).doesNotContain("secretname");
    }

    @Test
    void impossibleInputIsRejected() {
        assertThatThrownBy(() -> new TimedWord("a", 500, 400, 0.9)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimedWord("a", 0, 300, 1.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimedWord("a", 0, 300, -0.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimedWord("a", -1, 300, 0.9)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SentenceSpan(3, 2)).isInstanceOf(IllegalArgumentException.class);
        // a sentence pointing past the last word
        assertThatThrownBy(() -> new Transcript(wordsAfterGaps(0), List.of(new SentenceSpan(0, 1)), 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Transcript(List.of(), List.of(), -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void settingsReadAsInTheRequirement() {
        assertThat(DEFAULT.pauseRecordMs()).isEqualTo(1200);
        assertThat(DEFAULT.pauseCountMs()).isEqualTo(5000);
        assertThat(DEFAULT.windowMs()).isEqualTo(10_000);
        assertThat(DEFAULT.minFinalWindowMs()).isEqualTo(5000);
        assertThat(DEFAULT.lowConfidenceBelow()).isEqualTo(0.5);
        assertThat(DEFAULT.runOnOverWords()).isEqualTo(40);
        assertThat(DEFAULT.habitMarkers()).containsExactlyInAnyOrder("so", "okay", "right");
        assertThat(DEFAULT.habitMarkerMinCount()).isEqualTo(3);
        assertThat(DEFAULT.clusterWindowMs()).isEqualTo(10_000);
        assertThat(DEFAULT.clusterMinFillers()).isEqualTo(3);
    }

    @Test
    void badSettingsAreRejected() {
        assertThatThrownBy(() -> new MetricsSettings(
                        1200,
                        1000,
                        10_000,
                        5000,
                        0.5,
                        40,
                        java.util.Set.of("so"),
                        3,
                        10_000,
                        3,
                        MetricsSettings.LAUNCH_DEFAULT.wordLists(),
                        PaceBand.LAUNCH_DEFAULT,
                        30_000,
                        3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MetricsSettings(
                        1200,
                        5000,
                        0,
                        5000,
                        0.5,
                        40,
                        java.util.Set.of("so"),
                        3,
                        10_000,
                        3,
                        MetricsSettings.LAUNCH_DEFAULT.wordLists(),
                        PaceBand.LAUNCH_DEFAULT,
                        30_000,
                        3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MetricsSettings(
                        1200,
                        5000,
                        10_000,
                        5000,
                        1.5,
                        40,
                        java.util.Set.of("so"),
                        3,
                        10_000,
                        3,
                        MetricsSettings.LAUNCH_DEFAULT.wordLists(),
                        PaceBand.LAUNCH_DEFAULT,
                        30_000,
                        3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MetricsSettings(
                        1200,
                        5000,
                        10_000,
                        5000,
                        0.5,
                        40,
                        java.util.Set.of("so"),
                        0,
                        10_000,
                        3,
                        MetricsSettings.LAUNCH_DEFAULT.wordLists(),
                        PaceBand.LAUNCH_DEFAULT,
                        30_000,
                        3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void habitMarkersAreMatchedWhateverCaseTheSettingsUse() {
        MetricsSettings upper = new MetricsSettings(
                1200,
                5000,
                10_000,
                5000,
                0.5,
                40,
                java.util.Set.of("So", "OKAY"),
                3,
                10_000,
                3,
                MetricsSettings.LAUNCH_DEFAULT.wordLists(),
                PaceBand.LAUNCH_DEFAULT,
                30_000,
                3);

        assertThat(upper.habitMarkers()).containsExactlyInAnyOrder("so", "okay");
        assertThat(MetricsV2.compute(sentences("so we left", "okay then"), upper)
                        .habitMarkerCount())
                .isEqualTo(2);
    }

    @Test
    void theFactsCannotBeChangedAfterTheyAreBuilt() {
        SpeechFacts facts = compute(wordsEvery(1000, 20), 20_000);

        assertThatThrownBy(() -> facts.windowWpm().add(999)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> facts.runOnSentences().add(1)).isInstanceOf(UnsupportedOperationException.class);
    }
}
