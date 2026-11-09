package app.stageminute.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Fix Library texts (03 F-032, C-70): {@code content/fixes/en.json}, copied into the jar, read once by fix id. The
 * label and the How line pass through exactly as written; a file with a bad entry is refused (the server then does not
 * start).
 */
class FixTextsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void everyEntryOfTheFileComesThroughAsWritten() {
        FixTexts texts = FixTexts.parse(FixTextsFixtures.file(), JSON);
        JsonNode fixes = JSON.readTree(FixTextsFixtures.file()).path("fixes");

        assertThat(texts.codes()).hasSize(fixes.size());
        for (var entry : fixes.properties()) {
            FixTexts.Text text = texts.text(entry.getKey()).orElseThrow();
            assertThat(text.label()).isEqualTo(entry.getValue().path("label").asString());
            assertThat(text.how()).isEqualTo(entry.getValue().path("how").asString());
        }
        assertThat(texts.text("add_closing").orElseThrow().label()).isEqualTo("Add a closing.");
        assertThat(texts.text("no_such_fix")).isEmpty();
    }

    @Test
    void aLabelOverTwentyTwoCharactersIsRefused() {
        String json = "{\"fixes\":{\"add_closing\":{\"label\":\"Add a closing, please now\",\"how\":\"End well.\"}}}";

        assertThatThrownBy(() -> FixTexts.parse(json, JSON))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("add_closing");
    }

    @Test
    void aMissingOrBlankTextIsRefused() {
        assertThatThrownBy(() -> FixTexts.parse("{\"fixes\":{\"add_closing\":{\"label\":\"Add a closing.\"}}}", JSON))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("add_closing");
        assertThatThrownBy(() ->
                        FixTexts.parse("{\"fixes\":{\"add_closing\":{\"label\":\" \",\"how\":\"End well.\"}}}", JSON))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FixTexts.parse("{\"about\":\"no fixes\"}", JSON))
                .isInstanceOf(IllegalStateException.class);
    }
}
