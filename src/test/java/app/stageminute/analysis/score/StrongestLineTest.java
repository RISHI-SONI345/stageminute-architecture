package app.stageminute.analysis.score;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * 03 F-035: the model chooses a sentence id; code maps its times and rejects a sentence that holds a word under 0.5
 * confidence; the line is hidden when null or when it is the same place as the fix moment. Expected values are worked
 * by hand.
 */
class StrongestLineTest {

    static final MetricsSettings METRICS = MetricsSettings.LAUNCH_DEFAULT;

    /**
     * Six words, two sentences (words 0..2 and 3..5). Word i runs from i x 1000 to i x 1000 + 500 ms, so sentence 0 is
     * 0 to 2500 ms and sentence 1 is 3000 to 5500 ms. All confidence 0.9 unless given.
     */
    private static Transcript transcript(double... confidences) {
        List<TimedWord> words = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            double confidence = i < confidences.length ? confidences[i] : 0.9;
            words.add(new TimedWord("w", i * 1000, i * 1000 + 500, confidence));
        }
        return new Transcript(words, List.of(new SentenceSpan(0, 2), new SentenceSpan(3, 5)), 60_000);
    }

    private static Optional<StrongestLineResult> pick(
            OptionalInt id, Transcript transcript, Optional<Moment> fixMoment) {
        return StrongestLine.pick(id, transcript, fixMoment, METRICS);
    }

    @Test
    void theChosenSentenceGetsItsWordsAndTimes() {
        StrongestLineResult line =
                pick(OptionalInt.of(1), transcript(), Optional.empty()).orElseThrow();

        assertThat(line.sentenceId()).isEqualTo(1);
        assertThat(line.firstWord()).isEqualTo(3);
        assertThat(line.lastWord()).isEqualTo(5);
        assertThat(line.startMs()).isEqualTo(3000);
        assertThat(line.endMs()).isEqualTo(5500);
    }

    @Test
    void noChosenSentenceMeansNoStrongestLine() {
        assertThat(pick(OptionalInt.empty(), transcript(), Optional.empty())).isEmpty();
    }

    @Test
    void aSentenceIdThatDoesNotExistMeansNoStrongestLine() {
        assertThat(pick(OptionalInt.of(2), transcript(), Optional.empty())).isEmpty();
        assertThat(pick(OptionalInt.of(-1), transcript(), Optional.empty())).isEmpty();
    }

    @Test
    void aWordUnderHalfConfidenceRejectsTheSentence() {
        // word 4 is at 0.49
        assertThat(pick(OptionalInt.of(1), transcript(0.9, 0.9, 0.9, 0.9, 0.49), Optional.empty()))
                .isEmpty();
    }

    @Test
    void aWordAtExactlyHalfConfidenceIsNotUnderHalf() {
        assertThat(pick(OptionalInt.of(1), transcript(0.9, 0.9, 0.9, 0.9, 0.5), Optional.empty()))
                .isPresent();
    }

    @Test
    void aSmallWordUnderHalfConfidenceRejectsToo() {
        // "a word under 0.5": any word of the sentence, not only content words. Word 3 starts the sentence.
        assertThat(pick(OptionalInt.of(1), transcript(0.9, 0.9, 0.9, 0.2), Optional.empty()))
                .isEmpty();
    }

    @Test
    void aLowConfidenceWordInAnotherSentenceChangesNothing() {
        // word 1 (sentence 0) is at 0.1; sentence 1 is clean
        assertThat(pick(OptionalInt.of(1), transcript(0.9, 0.1), Optional.empty()))
                .isPresent();
    }

    @Test
    void theLineComesFromTheSettingsConfidenceLine() {
        MetricsSettings stricter = new MetricsSettings(
                METRICS.pauseRecordMs(),
                METRICS.pauseCountMs(),
                METRICS.windowMs(),
                METRICS.minFinalWindowMs(),
                0.7,
                METRICS.runOnOverWords(),
                METRICS.habitMarkers(),
                METRICS.habitMarkerMinCount(),
                METRICS.clusterWindowMs(),
                METRICS.clusterMinFillers(),
                METRICS.wordLists(),
                METRICS.paceFlagBand(),
                METRICS.paceFlagMs(),
                METRICS.maxMisheardWords());

        assertThat(StrongestLine.pick(
                        OptionalInt.of(1), transcript(0.9, 0.9, 0.9, 0.9, 0.6), Optional.empty(), stricter))
                .isEmpty();
        assertThat(pick(OptionalInt.of(1), transcript(0.9, 0.9, 0.9, 0.9, 0.6), Optional.empty()))
                .isPresent();
    }

    // ---- hidden when it is the same place as the fix moment
    // ------------------------------------------------------------

    @Test
    void aFixMomentInsideTheSentenceHidesTheLine() {
        Moment pause = new Moment(MomentKind.PAUSE, 4000, 4500);

        assertThat(pick(OptionalInt.of(1), transcript(), Optional.of(pause))).isEmpty();
    }

    @Test
    void aFixMomentInAnotherSentenceLeavesTheLine() {
        Moment pause = new Moment(MomentKind.PAUSE, 1000, 2000);

        assertThat(pick(OptionalInt.of(1), transcript(), Optional.of(pause))).isPresent();
    }

    @Test
    void aFixMomentThatOnlyTouchesTheSentenceLeavesTheLine() {
        // Deepgram often gives a word an end time equal to the next word's start, so a place that ends exactly where
        // the
        // sentence starts (or starts where it ends) shares no time with it. Sentence 1 runs 3000 to 5500.
        Moment endsAtItsStart = new Moment(MomentKind.FILLER_CLUSTER, 2500, 3000);
        Moment startsAtItsEnd = new Moment(MomentKind.PAUSE, 5500, 7000);

        assertThat(pick(OptionalInt.of(1), transcript(), Optional.of(endsAtItsStart)))
                .isPresent();
        assertThat(pick(OptionalInt.of(1), transcript(), Optional.of(startsAtItsEnd)))
                .isPresent();
    }

    @Test
    void aFixMomentThatSharesEvenOneMillisecondHidesTheLine() {
        Moment intoItsStart = new Moment(MomentKind.FILLER_CLUSTER, 2500, 3001);
        Moment intoItsEnd = new Moment(MomentKind.PAUSE, 5499, 7000);

        assertThat(pick(OptionalInt.of(1), transcript(), Optional.of(intoItsStart)))
                .isEmpty();
        assertThat(pick(OptionalInt.of(1), transcript(), Optional.of(intoItsEnd)))
                .isEmpty();
    }

    @Test
    void aFixMomentThatJustMissesTheSentenceLeavesTheLine() {
        Moment before = new Moment(MomentKind.FILLER_CLUSTER, 2500, 2999);

        assertThat(pick(OptionalInt.of(1), transcript(), Optional.of(before))).isPresent();
    }
}
