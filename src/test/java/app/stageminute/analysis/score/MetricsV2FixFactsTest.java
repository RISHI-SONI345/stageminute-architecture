package app.stageminute.analysis.score;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The three numbers the Fix Library needs from Metrics v2 (03 F-032): the length of the speech ("under 40 words or 20
 * s of speech"), the longest sentence ("longest 30 or less") and which confirmed fillers form a cluster (the moment
 * on the take map). Expected values are worked by hand.
 */
class MetricsV2FixFactsTest {

    static final MetricsSettings DEFAULT = MetricsSettings.LAUNCH_DEFAULT;

    /** Words are 300 ms long; {@code gapsMs[i]} is the silence before word i (the first is the start time). */
    private static List<TimedWord> wordsAfterGaps(int... gapsMs) {
        List<TimedWord> words = new ArrayList<>();
        int end = 0;
        for (int gap : gapsMs) {
            int start = end + gap;
            end = start + 300;
            words.add(new TimedWord("w", start, end, 0.9));
        }
        return words;
    }

    @Test
    void speechTimeRunsFromTheFirstWordStartToTheLastWordEnd() {
        // words at 0-300, 400-700, 800-1100: speech is 1100 ms
        List<TimedWord> words = wordsAfterGaps(0, 100, 100);
        SpeechFacts facts = MetricsV2.compute(new Transcript(words, List.of(new SentenceSpan(0, 2)), 30_000), DEFAULT);

        assertThat(facts.speechMs()).isEqualTo(1100);
    }

    @Test
    void silenceBeforeTheFirstWordIsNotSpeechTime() {
        // words at 2000-2300 and 2400-2700: speech is 700 ms, not 2700
        List<TimedWord> words = wordsAfterGaps(2000, 100);
        SpeechFacts facts = MetricsV2.compute(new Transcript(words, List.of(new SentenceSpan(0, 1)), 30_000), DEFAULT);

        assertThat(facts.speechMs()).isEqualTo(700);
    }

    @Test
    void noWordsGiveNoSpeechTimeAndNoSentenceLength() {
        SpeechFacts facts = MetricsV2.compute(new Transcript(List.of(), List.of(), 30_000), DEFAULT);

        assertThat(facts.speechMs()).isZero();
        assertThat(facts.longestSentenceWords()).isZero();
    }

    @Test
    void longestSentenceIsTheWordCountOfTheBiggestSentence() {
        // 10 words in two sentences: words 0..2 (3 words) and 3..9 (7 words)
        List<TimedWord> words = wordsAfterGaps(0, 100, 100, 100, 100, 100, 100, 100, 100, 100);
        Transcript transcript = new Transcript(words, List.of(new SentenceSpan(0, 2), new SentenceSpan(3, 9)), 30_000);

        assertThat(MetricsV2.compute(transcript, DEFAULT).longestSentenceWords())
                .isEqualTo(7);
    }

    @Test
    void fillerClusterGroupsListTheFillersOfEachClusterInTimeOrder() {
        // 1000, 2000, 3000 are one cluster (3 within 10 s). 20 000, 21 000, 25 000 are a second (all within 10 s of
        // 20 000). 40 000 is alone.
        List<List<Integer>> groups =
                MetricsV2.fillerClusterGroups(List.of(25_000, 1000, 3000, 20_000, 2000, 21_000, 40_000), DEFAULT);

        assertThat(groups).containsExactly(List.of(1000, 2000, 3000), List.of(20_000, 21_000, 25_000));
    }

    @Test
    void twoFillersAreNotACluster() {
        assertThat(MetricsV2.fillerClusterGroups(List.of(1000, 2000), DEFAULT)).isEmpty();
    }

    @Test
    void aFillerExactlyTenSecondsAfterTheFirstStartsTheNextCluster() {
        // 0, 4000, 9999 are one cluster (9999 is under 10 s after 0); 10 000 is not in it and is alone.
        assertThat(MetricsV2.fillerClusterGroups(List.of(0, 4000, 9999, 10_000), DEFAULT))
                .containsExactly(List.of(0, 4000, 9999));
    }
}
