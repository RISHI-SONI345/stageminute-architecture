package app.stageminute.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code speech_data} cannot reach a log line by accident (rule 4, F-026): every way of printing it gives {@code
 * [redacted]}, also for the model's rewording that travels with it (C-71).
 */
class SpeechDataViewTest {

    private static final SpeechDataView SPEECH = new SpeechDataView(
            "our checkout was slow",
            List.of(new SpeechDataView.Word("checkout", 0, 400, 0.98)),
            List.of(new SpeechDataView.Sentence(0, 0, 400)),
            "We rebuilt the checkout.",
            Map.of("0", "was loading"));

    @Test
    void printingTheWordsOrTheRewordingGivesRedacted() {
        String printed = SPEECH + " " + String.format("%s", SPEECH) + " " + SPEECH.words();

        assertThat(printed).doesNotContain("checkout").doesNotContain("rebuilt").doesNotContain("loading");
        assertThat(SPEECH.toString()).isEqualTo("[redacted]");
    }

    @Test
    void aTakeWithNoRewordingHasAnEmptyMapAndNoRewrite() {
        SpeechDataView none = new SpeechDataView("", List.of(), List.of(), null, Map.of());

        assertThat(none.rewrite()).isNull();
        assertThat(none.languageTries()).isEmpty();
    }
}
