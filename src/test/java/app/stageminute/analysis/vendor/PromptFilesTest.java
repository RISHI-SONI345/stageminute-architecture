package app.stageminute.analysis.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The prompt and schema versions come from the files' own text (AD-125, AD-151), so a trace to a report is exact. */
class PromptFilesTest {

    private static final String SCHEMA = "{\"type\":\"object\"}";

    @Test
    void theVersionIsMadeFromTheTextAndChangesWhenOneLetterChanges() {
        PromptFiles.Loaded a = PromptFiles.of("Label the answer.", SCHEMA);
        PromptFiles.Loaded sameText = PromptFiles.of("Label the answer.", SCHEMA);
        PromptFiles.Loaded changed = PromptFiles.of("Label the answers.", SCHEMA);

        assertThat(a.promptVersion()).matches("v2-[0-9a-f]{8}");
        assertThat(sameText.promptVersion()).isEqualTo(a.promptVersion());
        assertThat(changed.promptVersion()).isNotEqualTo(a.promptVersion());
        assertThat(changed.schemaVersion()).isEqualTo(a.schemaVersion());
    }

    @Test
    void theSchemaVersionChangesWithTheSchemaAndNotWithThePrompt() {
        PromptFiles.Loaded a = PromptFiles.of("Label the answer.", SCHEMA);
        PromptFiles.Loaded other = PromptFiles.of("Label the answer.", "{\"type\":\"array\"}");

        assertThat(a.schemaVersion()).matches("v2-[0-9a-f]{8}");
        assertThat(other.schemaVersion()).isNotEqualTo(a.schemaVersion());
        assertThat(other.promptVersion()).isEqualTo(a.promptVersion());
    }

    @Test
    void windowsLineEndingsGiveTheSameVersionAsUnixOnes() {
        assertThat(PromptFiles.of("one\r\ntwo\r\n", SCHEMA).promptVersion())
                .isEqualTo(PromptFiles.of("one\ntwo\n", SCHEMA).promptVersion());
    }

    @Test
    void aSchemaThatIsNotJsonStopsTheLoad() {
        assertThatThrownBy(() -> PromptFiles.of("x", "{not json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema");
    }

    @Test
    void theShippedFilesLoadAndCarryNoFakeVersion() {
        PromptFiles.Loaded files = PromptFiles.fromClasspath();

        assertThat(files.prompt()).contains("You label one spoken answer");
        assertThat(files.promptVersion()).matches("v2-[0-9a-f]{8}").doesNotContain("fake");
        assertThat(files.schemaVersion()).matches("v2-[0-9a-f]{8}").doesNotContain("fake");
        assertThat(files.schema().path("properties").has("steps")).isTrue();
    }
}
