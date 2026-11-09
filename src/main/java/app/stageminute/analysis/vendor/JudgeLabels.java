package app.stageminute.analysis.vendor;

import app.stageminute.analysis.score.FillerLabel;
import app.stageminute.analysis.score.LanguageLevels;
import app.stageminute.analysis.score.StepStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * The answer of the one call (03 F-029): labels, and a few lines of the model's words. No number here is a score;
 * code builds every number from the labels. The words are checked by the pipeline before they reach a report.
 *
 * @param steps the four step labels in the style's order
 * @param stepSentences for each step, the sentence id that shows it, or null when none does
 * @param relevance relevance level 1 to 4
 * @param language clarity, conciseness and accuracy levels
 * @param fillers the label of each in-context filler candidate, by candidate id
 * @param runOn whether a long sentence is a run-on
 * @param verdict the model's one line (at most 15 words), or null
 * @param tryLine the Try line for the fix (the model words it, code chooses the fix), or null
 * @param rewrite "One way to say it" (at most 60 words), or null
 * @param strongestSentence the sentence id of the strongest line, if any
 * @param strongestWhy why that line is strong, or null
 * @param languageItems grammar and word-choice items, by position, with the suggested wording
 * @param answeredModel the model id the vendor says served this answer (a real judge only), or null
 */
public record JudgeLabels(
        List<StepStatus> steps,
        List<@Nullable Integer> stepSentences,
        int relevance,
        LanguageLevels language,
        Map<Integer, FillerLabel> fillers,
        boolean runOn,
        @Nullable String verdict,
        @Nullable String tryLine,
        @Nullable String rewrite,
        OptionalInt strongestSentence,
        @Nullable String strongestWhy,
        List<LanguageItem> languageItems,
        @Nullable String answeredModel) {

    /**
     * One grammar, word-choice or swap item.
     *
     * @param kind {@code grammar}, {@code word_choice} or {@code swap}
     * @param sentence the sentence id
     * @param firstWord position of the first word it is about
     * @param lastWord position of the last word it is about
     * @param suggestion the model's suggested wording
     */
    public record LanguageItem(String kind, int sentence, int firstWord, int lastWord, String suggestion) {

        @Override
        public String toString() {
            return "LanguageItem[" + kind + ", sentence " + sentence + "]";
        }
    }

    /** Labels from a judge that does not say which model served them (the fake). */
    public JudgeLabels(
            List<StepStatus> steps,
            List<@Nullable Integer> stepSentences,
            int relevance,
            LanguageLevels language,
            Map<Integer, FillerLabel> fillers,
            boolean runOn,
            @Nullable String verdict,
            @Nullable String tryLine,
            @Nullable String rewrite,
            OptionalInt strongestSentence,
            @Nullable String strongestWhy,
            List<LanguageItem> languageItems) {
        this(
                steps,
                stepSentences,
                relevance,
                language,
                fillers,
                runOn,
                verdict,
                tryLine,
                rewrite,
                strongestSentence,
                strongestWhy,
                languageItems,
                null);
    }

    public JudgeLabels {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(strongestSentence, "strongestSentence");
        steps = List.copyOf(steps);
        stepSentences = Collections.unmodifiableList(new ArrayList<>(stepSentences));
        fillers = Map.copyOf(fillers);
        languageItems = List.copyOf(languageItems);
    }

    @Override
    public String toString() {
        return "JudgeLabels[redacted]";
    }
}
