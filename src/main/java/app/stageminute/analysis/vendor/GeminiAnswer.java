package app.stageminute.analysis.vendor;

import app.stageminute.analysis.score.FillerLabel;
import app.stageminute.analysis.score.LanguageLevels;
import app.stageminute.analysis.score.StepStatus;
import com.networknt.schema.Schema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads Gemini's answer (03 F-029). "Valid JSON is not usable JSON": after parsing, every field is checked again here,
 * against the same schema file the vendor was given and against what this take actually sent (a sentence id that
 * exists, a filler id that was a candidate), so a schema rule the vendor ignores cannot get through. Anything that
 * fails is "invalid output" and the caller retries once. No text of the answer is kept in an error or a log.
 *
 * <p>Model text that holds a number is passed on untouched: {@code LabelValidator} drops it in the pipeline (rule 5).
 */
final class GeminiAnswer {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MODELS_PREFIX = "models/";

    /**
     * What an answer was.
     *
     * @param labels the labels, or null when the output is not usable
     * @param inputTokens prompt tokens the vendor says it read
     * @param outputTokens answer and thinking tokens the vendor says it wrote
     * @param hasUsage whether the answer carried a usage report at all
     * @param answeredModel the model id the vendor says served the answer, without any {@code models/} prefix
     */
    record Parsed(
            @Nullable JudgeLabels labels,
            long inputTokens,
            long outputTokens,
            boolean hasUsage,
            @Nullable String answeredModel) {}

    private GeminiAnswer() {}

    static Parsed parse(byte[] body, Schema schema, GeminiInput.Rendered sent) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JacksonException e) {
            return new Parsed(null, 0, 0, false, null);
        }
        JsonNode usage = root.path("usageMetadata");
        boolean hasUsage = usage.isObject();
        long input = usage.path("promptTokenCount").asLong(0);
        long output = usage.path("candidatesTokenCount").asLong(0)
                + usage.path("thoughtsTokenCount").asLong(0);
        String model = answeredModel(root.path("modelVersion"));
        JudgeLabels labels = null;
        try {
            labels = labels(root, schema, sent, model);
        } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
            // not usable: fall through with no labels; the error carries none of the answer
        }
        return new Parsed(labels, Math.max(0, input), Math.max(0, output), hasUsage, model);
    }

    private static @Nullable String answeredModel(JsonNode node) {
        if (!node.isString() || node.asString().isBlank()) {
            return null;
        }
        String id = node.asString().trim();
        return id.startsWith(MODELS_PREFIX) ? id.substring(MODELS_PREFIX.length()) : id;
    }

    private static @Nullable JudgeLabels labels(
            JsonNode root, Schema schema, GeminiInput.Rendered sent, @Nullable String model) {
        JsonNode candidate = root.path("candidates").path(0);
        if (!"STOP".equals(candidate.path("finishReason").asString(""))) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (!part.path("thought").asBoolean(false) && part.path("text").isString()) {
                text.append(part.path("text").asString());
            }
        }
        if (text.toString().isBlank()) {
            return null;
        }
        JsonNode labels = JSON.readTree(text.toString());
        if (!labels.isObject() || !schema.validate(labels).isEmpty()) {
            return null;
        }
        return usable(labels, sent, model);
    }

    /** The checks the schema cannot make: the ids must exist in what was sent. */
    private static @Nullable JudgeLabels usable(JsonNode labels, GeminiInput.Rendered sent, @Nullable String model) {
        int sentences = sent.sentenceCount();
        List<StepStatus> steps = new ArrayList<>();
        for (JsonNode step : labels.path("steps")) {
            steps.add(StepStatus.valueOf(step.asString().toUpperCase(Locale.ROOT)));
        }
        List<@Nullable Integer> stepSentences = new ArrayList<>();
        for (JsonNode id : labels.path("step_sentences")) {
            if (id.isNull()) {
                stepSentences.add(null);
            } else if (id.asInt() >= sentences) {
                return null;
            } else {
                stepSentences.add(id.asInt());
            }
        }
        Map<Integer, FillerLabel> fillers = new HashMap<>();
        for (JsonNode filler : labels.path("fillers")) {
            int id = filler.path("id").asInt();
            if (!sent.candidateIds().contains(id)
                    || fillers.put(
                                    id,
                                    FillerLabel.valueOf(
                                            filler.path("label").asString().toUpperCase(Locale.ROOT)))
                            != null) {
                return null;
            }
        }
        OptionalInt strongest = OptionalInt.empty();
        if (!labels.path("strongest_sentence").isNull()) {
            if (labels.path("strongest_sentence").asInt() >= sentences) {
                return null;
            }
            strongest = OptionalInt.of(labels.path("strongest_sentence").asInt());
        }
        List<JudgeLabels.LanguageItem> items = new ArrayList<>();
        for (JsonNode item : labels.path("language_items")) {
            if (item.path("sentence").asInt() >= sentences
                    || item.path("first_word").asInt() > item.path("last_word").asInt()) {
                return null;
            }
            items.add(new JudgeLabels.LanguageItem(
                    item.path("kind").asString(),
                    item.path("sentence").asInt(),
                    item.path("first_word").asInt(),
                    item.path("last_word").asInt(),
                    item.path("suggestion").asString()));
        }
        return new JudgeLabels(
                steps,
                stepSentences,
                labels.path("relevance").asInt(),
                new LanguageLevels(
                        labels.path("clarity").asInt(),
                        labels.path("conciseness").asInt(),
                        labels.path("accuracy").asInt()),
                fillers,
                labels.path("run_on").asBoolean(),
                text(labels.path("verdict")),
                text(labels.path("try_line")),
                text(labels.path("rewrite")),
                strongest,
                text(labels.path("strongest_why")),
                items,
                model);
    }

    private static @Nullable String text(JsonNode node) {
        return node.isString() && !node.asString().isBlank() ? node.asString() : null;
    }
}
