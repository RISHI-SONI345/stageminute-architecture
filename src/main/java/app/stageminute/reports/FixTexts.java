package app.stageminute.reports;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Fix Library texts (03 F-032, C-63, C-70): one label (at most {@value #LABEL_MAX_CHARS} characters) and one How
 * line per fix id, from {@code content/fixes/en.json}. They are the server's own words, never the model's; the report's
 * {@code fix_first.label} and {@code how} and the carried focus label come from here. The texts pass through exactly as
 * written: nothing is computed from them. The file's other rules (no digit, no banned glossary word, one entry per
 * {@code FixId}) are checked by {@code npm run check:fixes} in {@code content/}; the server checks again at start that
 * every fix id has a text ({@code analysis.internal.Pipeline}).
 */
// Not final: Spring wraps module beans in a proxy for observability, and a final class cannot be wrapped.
public class FixTexts {

    /** The longest label the Fix first card and the button hold (F-032). */
    public static final int LABEL_MAX_CHARS = 22;

    /** One fix's label and How line. */
    public record Text(String label, String how) {}

    private final Map<String, Text> texts;

    FixTexts(Map<String, Text> texts) { // not private: the observability proxy needs a visible one
        this.texts = Map.copyOf(texts);
    }

    /**
     * Reads the file's {@code fixes} object.
     *
     * @throws IllegalStateException naming the fix id when an entry has no label, a blank one, one over {@value
     *     #LABEL_MAX_CHARS} characters, or no How line; or when there is no {@code fixes} object
     */
    public static FixTexts parse(String json, JsonMapper mapper) {
        JsonNode fixes = mapper.readTree(json).path("fixes");
        if (!fixes.isObject() || fixes.isEmpty()) {
            throw new IllegalStateException("The fix texts file has no fixes");
        }
        Map<String, Text> texts = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : fixes.properties()) {
            String label = entry.getValue().path("label").asString("");
            String how = entry.getValue().path("how").asString("");
            if (label.isBlank() || how.isBlank() || label.length() > LABEL_MAX_CHARS) {
                throw new IllegalStateException("The fix text of " + entry.getKey() + " needs a label of 1 to "
                        + LABEL_MAX_CHARS + " characters and a How line");
            }
            texts.put(entry.getKey(), new Text(label, how));
        }
        return new FixTexts(texts);
    }

    /** The texts of one fix id, or empty for an id the file does not have. */
    public Optional<Text> text(String fixCode) {
        return Optional.ofNullable(texts.get(fixCode));
    }

    /** Every fix id the file has. */
    public Set<String> codes() {
        return texts.keySet();
    }

    @Override
    public String toString() {
        return "FixTexts[" + texts.size() + " fixes]";
    }
}
