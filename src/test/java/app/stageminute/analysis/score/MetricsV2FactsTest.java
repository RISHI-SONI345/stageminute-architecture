package app.stageminute.analysis.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 03 F-027 and F-031 as written in requirements version 1.7: articulation rate, average pace, the 30 s pace flag,
 * filler candidates (always counted, judged in context, hedges) and mis-heard content words. Expected values are
 * worked by hand from the definitions in the requirement.
 */
class MetricsV2FactsTest {

    static final MetricsSettings DEFAULT = MetricsSettings.LAUNCH_DEFAULT;

    /** Each word is 300 ms long; {@code gapsMs[i]} is the silence before word i (the first is the start time). */
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

    private static SpeechFacts compute(List<TimedWord> words, int durationMs) {
        List<SentenceSpan> sentences = words.isEmpty() ? List.of() : List.of(new SentenceSpan(0, words.size() - 1));
        return MetricsV2.compute(new Transcript(words, sentences, durationMs), DEFAULT);
    }

    /** One sentence from the text. Words are 300 ms long with 100 ms between them. Confidence 0.9 unless given. */
    private static Transcript say(String text, double... confidences) {
        String[] tokens = text.split(" ", -1);
        List<TimedWord> words = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            double confidence = i < confidences.length ? confidences[i] : 0.9;
            words.add(new TimedWord(tokens[i], i * 400, i * 400 + 300, confidence));
        }
        return new Transcript(words, List.of(new SentenceSpan(0, words.size() - 1)), tokens.length * 400 + 1000);
    }

    // ---- articulation rate and average pace ------------------------------------------------------------------

    @Test
    void articulationRateIsWordsOverSpeakingTimeWithoutTheRecordedPauses() {
        // 8 words; the take runs from 0 to 4500 ms; one recorded pause of 1500 ms.
        // Speaking time 4500 - 1500 = 3000 ms -> 8 x 60 000 / 3000 = 160. Average pace over the whole 4500 ms is 106.67
        // -> 107.
        SpeechFacts facts = compute(wordsAfterGaps(0, 100, 100, 100, 1500, 100, 100, 100), 30_000);

        assertThat(facts.articulationWpm()).isEqualTo(160);
        assertThat(facts.averageWpm()).isEqualTo(107);
    }

    @Test
    void aPauseUnderTheRecordLineStaysInSpeakingTime() {
        // gaps of 1199 ms are not recorded pauses, so nothing is taken out: 3 words from 0 to 3298 ms
        // (3 x 300 + 2 x 1199) -> 3 x 60 000 / 3298 = 54.58 -> 55
        SpeechFacts facts = compute(wordsAfterGaps(0, 1199, 1199), 30_000);

        assertThat(facts.articulationWpm()).isEqualTo(55);
        assertThat(facts.averageWpm()).isEqualTo(55);
    }

    @Test
    void noWordsOrNoTimeGiveZero() {
        SpeechFacts none = compute(List.of(), 30_000);
        assertThat(none.articulationWpm()).isZero();
        assertThat(none.averageWpm()).isZero();

        // a word with no length has no speaking time
        SpeechFacts instant = MetricsV2.compute(
                new Transcript(List.of(new TimedWord("a", 100, 100, 0.9)), List.of(new SentenceSpan(0, 0)), 30_000),
                DEFAULT);
        assertThat(instant.articulationWpm()).isZero();
        assertThat(instant.averageWpm()).isZero();
    }

    // ---- the 30 s pace flag ----------------------------------------------------------------------------------

    /** {@code wordsPerWindow[w]} words spread evenly over 10 s window w, each 100 ms long. 5 words = 30 wpm, 20 = 120. */
    private static List<TimedWord> windowWords(int... wordsPerWindow) {
        List<TimedWord> words = new ArrayList<>();
        for (int w = 0; w < wordsPerWindow.length; w++) {
            for (int i = 0; i < wordsPerWindow[w]; i++) {
                int start = w * 10_000 + i * (10_000 / wordsPerWindow[w]);
                words.add(new TimedWord("w", start, start + 100, 0.9));
            }
        }
        return words;
    }

    @ParameterizedTest(name = "windows {0} in {1} ms: {2} ms outside the band, flag {3}")
    @CsvSource(
            delimiter = '|',
            value = {
                // three slow 10 s windows = 30 s outside: the flag fires at exactly 30 s
                "5;5;5;20|40000|30000|true",
                // 20 s outside: no flag
                "5;5;20;20|40000|20000|false",
                // a silent window is outside the band too
                "0;0;0;20|40000|30000|true",
                // two slow full windows and a slow 5 s last window = 25 s: no flag
                "5;5;3|25000|25000|false",
                // the same take run to 30 s (a third full slow window) = 30 s: flag
                "5;5;5|30000|30000|true",
                // a fast 5 s last window (10 words = 120 wpm) is inside
                "20;20;20;10|35000|0|false"
            })
    void thePaceFlagFiresAfterThirtySecondsInTotalOutsideTheBand(
            String perWindow, int durationMs, int outsideMs, boolean flag) {
        int[] counts = java.util.Arrays.stream(perWindow.split(";", -1))
                .mapToInt(Integer::parseInt)
                .toArray();

        SpeechFacts facts = compute(windowWords(counts), durationMs);

        assertThat(facts.outsideBandMs()).isEqualTo(outsideMs);
        assertThat(facts.paceFlag()).isEqualTo(flag);
    }

    // ---- filler candidates -----------------------------------------------------------------------------------

    private static List<FillerCandidate> candidates(String text) {
        return MetricsV2.compute(say(text), DEFAULT).fillerCandidates();
    }

    @Test
    void candidatesAreFoundInOrderWithTheirKindAndWordPositions() {
        // um(0) so(1) I think(2-3) we(4) should(5) uh(6) go(7) you know(8-9) like(10) now(11)
        List<FillerCandidate> found = candidates("um so I think we should uh go you know like now");

        assertThat(found)
                .extracting(
                        FillerCandidate::id,
                        FillerCandidate::kind,
                        FillerCandidate::firstWord,
                        FillerCandidate::lastWord)
                .containsExactly(
                        tuple(0, FillerKind.ALWAYS_COUNTED, 0, 0),
                        tuple(1, FillerKind.IN_CONTEXT, 1, 1),
                        tuple(2, FillerKind.HEDGE, 2, 3),
                        tuple(3, FillerKind.ALWAYS_COUNTED, 6, 6),
                        tuple(4, FillerKind.IN_CONTEXT, 8, 9),
                        tuple(5, FillerKind.IN_CONTEXT, 10, 10));
    }

    @ParameterizedTest(name = "\"{0}\" is {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                // always counted: um, uh, er, erm, ah, hmm (case and punctuation do not matter)
                "Um,|ALWAYS_COUNTED",
                "UH.|ALWAYS_COUNTED",
                "er|ALWAYS_COUNTED",
                "erm|ALWAYS_COUNTED",
                "ah|ALWAYS_COUNTED",
                "hmm|ALWAYS_COUNTED",
                // judged in context: like, so, basically, actually, literally
                "like|IN_CONTEXT",
                "So|IN_CONTEXT",
                "basically|IN_CONTEXT",
                "actually|IN_CONTEXT",
                "literally|IN_CONTEXT",
                // maybe is a hedge
                "maybe|HEDGE"
            })
    void oneWordCandidates(String text, FillerKind kind) {
        assertThat(candidates(text)).extracting(FillerCandidate::kind).containsExactly(kind);
    }

    @ParameterizedTest(name = "\"{0}\" is {1}")
    @CsvSource({
        "you know, IN_CONTEXT",
        "I mean, IN_CONTEXT",
        "you see, IN_CONTEXT",
        // I guess is on both lists in the requirement: the in-context list wins
        "I guess, IN_CONTEXT",
        "kind of, HEDGE",
        "sort of, HEDGE",
        "I think, HEDGE"
    })
    void phraseCandidates(String text, FillerKind kind) {
        List<FillerCandidate> found = candidates(text);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).kind()).isEqualTo(kind);
        assertThat(found.get(0).lastWord()).isEqualTo(1);
    }

    @Test
    void agreementSoundsAndOrdinaryWordsAreNotCandidates() {
        assertThat(candidates("mhmm uh-huh mm-mm okay then we know it is right"))
                .isEmpty();
    }

    @Test
    void aCandidateStartsWhereItsFirstWordStarts() {
        // "go" is word 0 (0 ms), "um" is word 1 (400 ms)
        assertThat(candidates("go um").get(0).startMs()).isEqualTo(400);
    }

    @Test
    void candidatesNeverOverlapAndTheLongestPhraseWins() {
        // "you know" is one candidate, not "you" then "know"; "kind of" is one hedge
        List<FillerCandidate> found = candidates("it was kind of you know fine");

        assertThat(found)
                .extracting(FillerCandidate::kind, FillerCandidate::firstWord, FillerCandidate::lastWord)
                .containsExactly(tuple(FillerKind.HEDGE, 2, 3), tuple(FillerKind.IN_CONTEXT, 4, 5));
    }

    @Test
    void theAlwaysCountedStartsFeedTheClusterCount() {
        // three confirmed fillers within 10 s make one cluster (F-027)
        List<Integer> starts = IntStream.of(0, 1, 2)
                .mapToObj(i -> candidates("um um um").get(i).startMs())
                .toList();

        assertThat(MetricsV2.fillerClusters(starts, DEFAULT)).isEqualTo(1);
    }

    // ---- mis-heard content words -----------------------------------------------------------------------------

    @Test
    void misheardWordsAreLowConfidenceContentWordsMaxThreeLowestFirst() {
        // the(0.2) quick(0.4) brown(0.3) fox(0.9) jumps(0.45) over(0.1) the(0.3) lazy(0.49) dog(0.5)
        Transcript t =
                say("the quick brown fox jumps over the lazy dog", 0.2, 0.4, 0.3, 0.9, 0.45, 0.1, 0.3, 0.49, 0.5);

        SpeechFacts facts = MetricsV2.compute(t, DEFAULT);

        // 7 words are under 0.5 (the, quick, brown, jumps, over, the, lazy). the and over are function words.
        // Content words under 0.5: quick 0.4, brown 0.3, jumps 0.45, lazy 0.49 -> the three lowest are brown, quick,
        // jumps,
        // listed by position.
        assertThat(facts.lowConfidenceWords()).isEqualTo(7);
        assertThat(facts.misheardWords()).containsExactly(1, 2, 4);
    }

    @Test
    void aFunctionWordIsNeverMisheardWhateverItsConfidence() {
        Transcript t = say("the a and of to", 0.1, 0.1, 0.1, 0.1, 0.1);

        SpeechFacts facts = MetricsV2.compute(t, DEFAULT);

        assertThat(facts.lowConfidenceWords()).isEqualTo(5);
        assertThat(facts.misheardWords()).isEmpty();
    }

    @Test
    void functionWordsAreMatchedWithoutCaseOrPunctuation() {
        Transcript t = say("The, AND I'm it’s", 0.1, 0.1, 0.1, 0.1);

        assertThat(MetricsV2.compute(t, DEFAULT).misheardWords()).isEmpty();
    }

    @Test
    void equalConfidenceKeepsTheEarlierWords() {
        Transcript t = say("alpha beta gamma delta", 0.3, 0.3, 0.3, 0.3);

        assertThat(MetricsV2.compute(t, DEFAULT).misheardWords()).containsExactly(0, 1, 2);
    }

    // ---- privacy and settings --------------------------------------------------------------------------------

    @Test
    void factsHoldPositionsAndKindsNeverText() {
        SpeechFacts facts = MetricsV2.compute(say("secretname um basically"), DEFAULT);

        assertThat(facts.toString()).doesNotContain("secretname").doesNotContain("basically");
        assertThat(facts.fillerCandidates().toString()).doesNotContain("um").doesNotContain("basically");
    }

    @Test
    void theFactsCannotBeChangedAfterTheyAreBuilt() {
        SpeechFacts facts = MetricsV2.compute(say("um like a thing"), DEFAULT);

        assertThatThrownBy(() -> facts.fillerCandidates().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> facts.misheardWords().add(1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void wordListsAndPaceFlagSettingsReadAsInTheRequirement() {
        WordLists lists = DEFAULT.wordLists();

        assertThat(lists.alwaysCounted()).containsExactlyInAnyOrder("um", "uh", "er", "erm", "ah", "hmm");
        assertThat(lists.inContext())
                .containsExactlyInAnyOrder(
                        "like", "so", "you know", "i mean", "basically", "actually", "literally", "i guess", "you see");
        assertThat(lists.hedges()).containsExactlyInAnyOrder("kind of", "sort of", "i think", "i guess", "maybe");
        assertThat(lists.functionWords()).contains("the", "a", "and", "of", "to", "um", "uh");
        assertThat(DEFAULT.paceFlagBand()).isEqualTo(new PaceBand(85, 240));
        assertThat(DEFAULT.paceFlagMs()).isEqualTo(30_000);
        assertThat(DEFAULT.maxMisheardWords()).isEqualTo(3);
    }

    @Test
    void badWordListsAreRejected() {
        assertThatThrownBy(() -> new WordLists(java.util.Set.of(), List.of("like"), List.of(), java.util.Set.of("a")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WordLists(java.util.Set.of("um"), List.of("like"), List.of(), java.util.Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
