package app.stageminute.analysis.vendor;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one place that builds the Gemini request (03 F-029, D-84): one call, the pinned model, the prompt as the system
 * instruction, the take as text, and the enum schema as the response schema. The settings are the most repeatable the
 * API offers: temperature 0 and a fixed seed (a model that ignores them is still one call; the answer id and the run-to-run gate show the effect) (F-029 names none, so this is our choice, AD-151). The key goes
 * in a header, never in the address.
 */
final class GeminiRequest {

    static final String CONTENT_TYPE = "application/json";
    static final String KEY_HEADER = "x-goog-api-key";

    private static final double TEMPERATURE = 0.0;
    private static final int SEED = 7;
    private static final int MAX_OUTPUT_TOKENS = 8192;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private GeminiRequest() {}

    static URI uri(String baseUrl, String model) {
        return URI.create(
                baseUrl + "/v1beta/models/" + URLEncoder.encode(model, StandardCharsets.UTF_8) + ":generateContent");
    }

    /**
     * @param thinkingBudgetTokens tokens the model may think before it answers; 0 or less names none, so the model's
     *     own default applies (setting {@code gemini_thinking_budget_tokens}, AD-154)
     */
    static byte[] body(PromptFiles.Loaded files, GeminiInput.Rendered input, int thinkingBudgetTokens) {
        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("temperature", TEMPERATURE);
        generation.put("seed", SEED);
        generation.put("maxOutputTokens", MAX_OUTPUT_TOKENS);
        generation.put("responseMimeType", "application/json");
        generation.put("responseJsonSchema", files.schema());
        if (thinkingBudgetTokens > 0) {
            generation.put("thinkingConfig", Map.of("thinkingBudget", thinkingBudgetTokens));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("systemInstruction", Map.of("parts", List.of(Map.of("text", files.prompt()))));
        root.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", input.json())))));
        root.put("generationConfig", generation);
        return JSON.writeValueAsBytes(root);
    }
}
