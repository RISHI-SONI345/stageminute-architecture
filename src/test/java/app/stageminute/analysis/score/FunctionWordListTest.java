package app.stageminute.analysis.score;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 03 F-027 (v1.7): a mis-heard content word is any word not on the short function-word list kept in server settings:
 * "articles, pronouns, auxiliaries, prepositions, conjunctions and the filler words themselves". The list is written
 * by hand (C-31; no library list is copied) and the founder approves the words. These tests keep it short, whole and
 * free of content words.
 */
class FunctionWordListTest {

    static final Set<String> LIST = WordLists.LAUNCH_DEFAULT.functionWords();

    @Test
    void theListIsShortAboutAHundredWords() {
        assertThat(LIST.size()).isBetween(90, 110);
    }

    @Test
    void itHoldsEachKindOfSmallWordTheRequirementNames() {
        assertThat(LIST).contains("a", "an", "the"); // articles
        assertThat(LIST).contains("i", "you", "he", "she", "it", "we", "they", "me", "my", "your", "their"); // pronouns
        assertThat(LIST).contains("is", "are", "was", "were", "be", "been", "have", "has", "had", "do", "does", "did");
        assertThat(LIST).contains("will", "would", "can", "could", "should"); // auxiliaries
        assertThat(LIST)
                .contains("of", "to", "in", "on", "at", "by", "for", "with", "from", "about", "over"); // prepositions
        assertThat(LIST).contains("and", "but", "or", "so", "if", "because", "than", "then"); // conjunctions
    }

    @Test
    void itHoldsTheFillerWordsThemselves() {
        assertThat(LIST).containsAll(WordLists.LAUNCH_DEFAULT.alwaysCounted());
        assertThat(LIST).contains("like", "basically", "actually", "literally");
    }

    @Test
    void itHoldsTheContractionsDeepgramWritesAsOneWord() {
        assertThat(LIST).contains("i'm", "it's", "don't", "that's", "can't", "you're");
    }

    @Test
    void itHoldsNoContentWord() {
        // everyday words that carry meaning: a low-confidence one is a real mis-heard word
        assertThat(LIST)
                .doesNotContain(
                        "know", "think", "mean", "guess", "see", "good", "make", "work", "time", "people", "team",
                        "manager", "project", "year", "thing", "kind", "sort", "maybe", "okay", "right");
    }

    @Test
    void everyWordIsLowerCaseWithNoPunctuationExceptTheApostrophe() {
        for (String word : LIST) {
            assertThat(word).isEqualTo(WordLists.normalise(word));
            assertThat(word).matches("[a-z']+");
        }
    }

    @Test
    void theListIsASettingNotALiteralInTheLogic() {
        // two words at 0.1: "alpha" and "beta". With the launch list both are mis-heard content words; with a list that
        // holds "alpha", only "beta" is.
        List<TimedWord> words = List.of(new TimedWord("alpha", 0, 300, 0.1), new TimedWord("beta", 400, 700, 0.1));
        Transcript transcript = new Transcript(words, List.of(new SentenceSpan(0, 1)), 30_000);
        WordLists custom = new WordLists(
                WordLists.LAUNCH_DEFAULT.alwaysCounted(),
                WordLists.LAUNCH_DEFAULT.inContext(),
                WordLists.LAUNCH_DEFAULT.hedges(),
                Set.of("alpha"));
        MetricsSettings d = MetricsSettings.LAUNCH_DEFAULT;
        MetricsSettings withCustom = new MetricsSettings(
                d.pauseRecordMs(),
                d.pauseCountMs(),
                d.windowMs(),
                d.minFinalWindowMs(),
                d.lowConfidenceBelow(),
                d.runOnOverWords(),
                d.habitMarkers(),
                d.habitMarkerMinCount(),
                d.clusterWindowMs(),
                d.clusterMinFillers(),
                custom,
                d.paceFlagBand(),
                d.paceFlagMs(),
                d.maxMisheardWords());

        assertThat(MetricsV2.compute(transcript, d).misheardWords()).containsExactly(0, 1);
        assertThat(MetricsV2.compute(transcript, withCustom).misheardWords()).containsExactly(1);
    }
}
