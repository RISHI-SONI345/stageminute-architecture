package app.stageminute.topics;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.support.DatabaseTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The server starts with the real files of {@code content/topics} (a Gradle step copies them into the jar, so the
 * same files serve the tests, the jar and the Node check). If one of them were bad, this test class could not
 * start the server at all.
 */
class TopicsStartupTest extends DatabaseTest {

    @Autowired
    TopicCatalog catalog;

    @Test
    void theFirstFortyTopicsAreInTheCatalog() {
        for (Style style : Style.values()) {
            for (Level level : Level.values()) {
                assertThat(catalog.pool(style, level))
                        .as("live topics of %s at %s", style.wire(), level.wire())
                        .hasSize(5);
            }
        }
    }

    @Test
    void thereIsASeedPerStyleAtTheLowestLevel() {
        for (Style style : Style.values()) {
            assertThat(catalog.pool(style, Level.STARTER))
                    .filteredOn(Topic::seed)
                    .hasSize(1);
        }
    }

    @Test
    void aStarTopicCarriesWhoAsks() {
        Topic star = catalog.get("star-int-001").orElseThrow();

        assertThat(star.roleContext()).isNotBlank();
        assertThat(star.speakS()).isEqualTo(90);
        assertThat(catalog.version()).matches("[0-9a-f]{12}");
    }
}
