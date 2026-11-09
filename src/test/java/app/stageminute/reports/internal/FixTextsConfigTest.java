package app.stageminute.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fix texts are read at start from the path named by {@code stageminute.content.fixes-path} (C-70). A path that
 * is blank or names no file stops the server at start, never at the first request (fails closed).
 */
class FixTextsConfigTest {

    private final FixTextsConfig config = new FixTextsConfig();
    private final DefaultResourceLoader loader = new DefaultResourceLoader();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void theDefaultPathReadsTheFileInTheJar() {
        assertThat(config.fixTexts(loader, "classpath:content-fixes/en.json", json)
                        .text("add_closing"))
                .isPresent();
    }

    @Test
    void aPathThatNamesNoFileStopsTheStart() {
        assertThatThrownBy(() -> config.fixTexts(loader, "classpath:content-fixes/missing.json", json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stageminute.content.fixes-path");
    }

    @Test
    void onlyAFileInTheJarOrOnDiskIsRead() { // never a web address fetched at start
        for (String path : new String[] {"https://example.com/en.json", "http://localhost/en.json", "/tmp/en.json"}) {
            assertThatThrownBy(() -> config.fixTexts(loader, path, json))
                    .as(path)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("classpath: or file:");
        }
    }

    @Test
    void aBlankPathStopsTheStart() {
        assertThatThrownBy(() -> config.fixTexts(loader, " ", json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stageminute.content.fixes-path");
    }
}
