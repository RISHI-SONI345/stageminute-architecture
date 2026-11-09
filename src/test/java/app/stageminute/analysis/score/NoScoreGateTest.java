package app.stageminute.analysis.score;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 03 F-037 (decided before the AI call): under 40 words or 20 s is a short take with no score; 40 or more words
 * under 30 s is scored with a caution. Boundaries come from the requirement text, not from the code.
 */
class NoScoreGateTest {

    static final GateThresholds DEFAULT = GateThresholds.LAUNCH_DEFAULT;

    private static GateResult check(int durationMs, int words) {
        return NoScoreGate.check(new GateInput(durationMs, words, true, 0), DEFAULT);
    }

    @ParameterizedTest(name = "{1} words in {0} ms reads {2}")
    @CsvSource({
        // a normal take
        "60000, 100, OK",
        // words: 39 is short, 40 is enough
        "60000, 39, SHORT_TAKE",
        "60000, 40, OK",
        // time: 19.999 s is short, 20 s is enough
        "19999, 100, SHORT_TAKE",
        "20000, 100, OK",
        // both short
        "10000, 12, SHORT_TAKE"
    })
    void shortTakeIsDecidedByWordsOrTime(int durationMs, int words, TakeStatus expected) {
        assertThat(check(durationMs, words).status()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{1} words in {0} ms: scored {2}")
    @CsvSource({"60000, 100, true", "60000, 39, false", "19999, 100, false", "20000, 40, true"})
    void aShortTakeGetsNoScore(int durationMs, int words, boolean scored) {
        assertThat(check(durationMs, words).scored()).isEqualTo(scored);
    }

    @ParameterizedTest(name = "{1} words in {0} ms: caution {2}")
    @CsvSource({
        // 40+ words but under 30 s: scored, with a caution
        "20000, 40, true",
        "29999, 80, true",
        // 30 s and up: no caution
        "30000, 80, false",
        "60000, 100, false"
    })
    void shortButEnoughIsScoredWithACaution(int durationMs, int words, boolean caution) {
        GateResult result = check(durationMs, words);

        assertThat(result.status()).isEqualTo(TakeStatus.OK);
        assertThat(result.scored()).isTrue();
        assertThat(result.shortCaution()).isEqualTo(caution);
    }

    @ParameterizedTest(name = "{1} words, English heard {2}, {0} ms reads {3}")
    @CsvSource({
        // nothing heard: a silent take is no_speech even when it is also short (EC-029, 14)
        "5000, 0, true, NO_SPEECH",
        "60000, 0, true, NO_SPEECH",
        // no words wins over "not English" (nothing to judge the language of)
        "60000, 0, false, NO_SPEECH",
        // another language (EC-149): decided before length, so a short foreign take reads not_english
        "60000, 100, false, NOT_ENGLISH",
        "8000, 10, false, NOT_ENGLISH"
    })
    void noSpeechAndNotEnglishComeBeforeLength(int durationMs, int words, boolean english, TakeStatus expected) {
        GateResult result = NoScoreGate.check(new GateInput(durationMs, words, english, 0), DEFAULT);

        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.scored()).isFalse();
    }

    @ParameterizedTest(name = "{1} of {0} words are low confidence reads {2}")
    @CsvSource({
        // F-036: "above 10%" - exactly 10% is not low audio
        "100, 0, OK",
        "100, 10, OK",
        "100, 11, LOW_AUDIO",
        "40, 4, OK",
        "40, 5, LOW_AUDIO",
        "50, 5, OK",
        "41, 5, LOW_AUDIO"
    })
    void lowAudioIsMoreThanTenPercentLowConfidenceWords(int words, int lowWords, TakeStatus expected) {
        GateResult result = NoScoreGate.check(new GateInput(60_000, words, true, lowWords), DEFAULT);

        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.scored()).isTrue();
    }

    @org.junit.jupiter.api.Test
    void lowAudioTakeStillCarriesTheShortCaution() {
        GateResult result = NoScoreGate.check(new GateInput(25_000, 50, true, 10), DEFAULT);

        assertThat(result.status()).isEqualTo(TakeStatus.LOW_AUDIO);
        assertThat(result.shortCaution()).isTrue();
    }

    @org.junit.jupiter.api.Test
    void aShortTakeStaysShortEvenWhenTheAudioIsPoor() {
        GateResult result = NoScoreGate.check(new GateInput(60_000, 39, true, 20), DEFAULT);

        assertThat(result.status()).isEqualTo(TakeStatus.SHORT_TAKE);
        assertThat(result.scored()).isFalse();
    }

    @ParameterizedTest(name = "{0} ms, {1} words, {2} low is rejected")
    @CsvSource({"-1, 10, 0", "1000, -1, 0", "1000, 10, -1", "1000, 10, 11"})
    void impossibleInputIsRejected(int durationMs, int words, int lowWords) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new GateInput(durationMs, words, true, lowWords))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "thresholds {0}, {1}, {2}, {3} are rejected")
    @CsvSource({
        // a gate that lets empty or instant takes through would score nothing as something
        "0, 40, 30000, 10",
        "20000, 0, 30000, 10",
        // the caution line cannot sit below the line for scoring at all
        "20000, 40, 19999, 10",
        // a share is 0..100 percent
        "20000, 40, 30000, -1",
        "20000, 40, 30000, 101"
    })
    void badThresholdsAreRejectedSoTheGateFailsClosed(int minMs, int minWords, int cautionMs, int sharePct) {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new GateThresholds(minMs, minWords, cautionMs, sharePct))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
