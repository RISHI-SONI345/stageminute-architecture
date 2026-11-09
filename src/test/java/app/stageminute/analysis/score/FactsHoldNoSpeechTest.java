package app.stageminute.analysis.score;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Privacy (root CLAUDE.md, privacy rules; D-11): no words, filler words or mis-heard words in facts, choices or
 * moments. The results of this ticket are stored (or sent) as numbers, ids and labels, so none of their fields may be
 * able to hold text. A new text field in one of these records fails this test.
 */
class FactsHoldNoSpeechTest {

    static Stream<Class<? extends Record>> recordsThatMayBeStoredOrLogged() {
        return Stream.of(
                SpeechFacts.class,
                TakeFacts.class,
                FillerCandidate.class,
                FillerFacts.class,
                FixChoice.class,
                FixSelection.class,
                PreviousTake.class,
                FocusCheckResult.class,
                FocusEvidence.class,
                Moment.class,
                TakeMoments.class,
                StrongestLineResult.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recordsThatMayBeStoredOrLogged")
    void noFieldCanHoldText(Class<? extends Record> type) {
        List<String> textFields = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            String typeName = component.getGenericType().getTypeName();
            if (typeName.contains("String") || typeName.contains("CharSequence")) {
                textFields.add(component.getName() + ": " + typeName);
            }
        }

        assertThat(textFields).as("text fields in %s", type.getSimpleName()).isEmpty();
    }

    @Test
    void aFixChoiceShowsNoWordsWhenPrinted() {
        // the id is the only text-like part, and it is a fixed code from the library
        FixChoice choice =
                FixLibrary.firstFix(Takes.strong().step(1, StepStatus.MISSING).build(), FixSettings.LAUNCH_DEFAULT);

        assertThat(choice.toString())
                .isEqualTo("FixChoice[id=ADD_REASON, target=1, carriedOver=false, nothingMajor=false]");
    }
}
