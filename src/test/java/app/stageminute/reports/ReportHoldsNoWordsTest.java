package app.stageminute.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Facts and findings hold numbers and positions only, never words (03 F-026 "Done when"; C-64; F-119: the short
 * {@code quotes} are the one place speech text is kept). This test reads the shape of {@link ReportV2} itself, so a
 * text field added to {@code facts} or {@code findings} fails here before any data is ever stored. Labels are enums
 * (fixed words chosen by code, like {@code strong} or {@code steady}), not speech.
 */
class ReportHoldsNoWordsTest {

    @Test
    void factsHaveNoFieldThatCanHoldText() {
        assertThat(textFields(ReportV2.Facts.class, "facts")).isEmpty();
    }

    @Test
    void findingsHaveNoFieldThatCanHoldText() {
        assertThat(textFields(ReportV2.Findings.class, "findings")).isEmpty();
    }

    /**
     * Every field of the whole report that can hold text, by name. The take's own words may sit only in {@code
     * quotes} and {@code strongest_line.quote} (F-119); the model's lines are {@code verdict}, {@code fix_first.try}
     * and {@code strongest_line.why}; the rest are ids, versions and the server's own texts. The model's rewording of
     * the answer ({@code rewrite}, {@code language_tries}) travels with {@code speech_data} and stays on the phone
     * (C-71), so it is not allowed here.
     */
    @Test
    void theReportHoldsTextOnlyInTheAllowedFields() {
        assertThat(textFields(ReportV2.class, "report"))
                .containsExactlyInAnyOrder(
                        "report.analysis_id",
                        "report.session_id",
                        "report.formula_version",
                        "report.model_version",
                        "report.prompt_version",
                        "report.schema_version",
                        "report.verdict",
                        "report.quotes",
                        "report.fix_first.id",
                        "report.fix_first.label",
                        "report.fix_first.how",
                        "report.fix_first.try_line",
                        "report.fix_first.moment",
                        "report.focus_check.fix_id",
                        "report.next_attempt.reason",
                        "report.next_focus.fix_code",
                        "report.carried_focus.fix_code",
                        "report.carried_focus.label",
                        "report.carried_focus.from_session_id",
                        "report.strongest_line.quote",
                        "report.strongest_line.why",
                        "report.moments[].id",
                        "report.trend");
    }

    @Test
    void aDisputeCarriesWordsOnlyInTheOptInNoteAndTranscriptAndNeverPrintsThem() { // F-042, C-67, AD-147
        assertThat(textFields(DisputeRequest.class, "dispute")).containsExactly("dispute.note", "dispute.transcript");

        DisputeRequest request = new DisputeRequest(DisputeArea.OTHER, null, "Zebracorn note", "Quixotic transcript");
        assertThat(request.toString()).doesNotContain("Zebracorn").doesNotContain("Quixotic");
    }

    /**
     * The patterns answer (03 F-056, "a test asserts no words are stored") holds a style code, two step codes and
     * dates, nothing a person said. The codes are chosen by the server from fixed lists, never taken from speech.
     */
    @Test
    void thePatternsAnswerHoldsOnlyStyleAndStepCodesAndDates() {
        assertThat(textFields(Patterns.class, "patterns"))
                .containsExactlyInAnyOrder(
                        "patterns.style",
                        "patterns.strength.criterion",
                        "patterns.opportunity.step",
                        "patterns.fixed_on_second.days[]");
    }

    @Test
    void theCheckFindsATextFieldWhenThereIsOne() {
        record Leaky(int words, List<String> fillerWords) {}
        assertThat(textFields(Leaky.class, "leaky")).containsExactly("leaky.filler_words[]");
    }

    /** Paths of every component that is, or contains, something other than a number, a yes/no or an enum. */
    private static List<String> textFields(Class<?> type, String path) {
        List<String> found = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            walk(component.getGenericType(), path + "." + snake(component.getName()), found);
        }
        return found;
    }

    private static void walk(Type type, String path, List<String> found) {
        if (type instanceof ParameterizedType parameterized) {
            Class<?> raw = (Class<?>) parameterized.getRawType();
            if (List.class.isAssignableFrom(raw)) {
                walk(parameterized.getActualTypeArguments()[0], path + "[]", found);
                return;
            }
            if (Map.class.isAssignableFrom(raw)) {
                found.add(path); // a map has text keys
                return;
            }
        }
        Class<?> cls = (Class<?>) type;
        if (cls.isPrimitive() || Number.class.isAssignableFrom(cls) || cls == Boolean.class || cls.isEnum()) {
            return;
        }
        if (cls.isRecord()) {
            found.addAll(textFields(cls, path));
            return;
        }
        found.add(path);
    }

    private static String snake(String camel) {
        return camel.replaceAll("([A-Z])", "_$1").toLowerCase(java.util.Locale.ROOT);
    }
}
