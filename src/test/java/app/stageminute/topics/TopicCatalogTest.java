package app.stageminute.topics;

import static app.stageminute.topics.Topics.catalog;
import static app.stageminute.topics.Topics.prep;
import static app.stageminute.topics.Topics.retired;
import static app.stageminute.topics.Topics.star;
import static app.stageminute.topics.Topics.withText;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * F-014 and AD-102: the catalog serves live topics of one style and level, still resolves a retired topic by id, and
 * has a version that changes exactly when what the phone would see changes.
 */
class TopicCatalogTest {

    @Test
    void poolHoldsOnlyTheLiveTopicsOfThatStyleAndLevel() {
        TopicCatalog catalog = catalog(List.of(
                prep("a", Level.STARTER, 1),
                prep("b", Level.STARTER, 2),
                prep("c", Level.INTERMEDIATE, 1),
                star("d", Level.STARTER, 1)));

        assertThat(catalog.pool(Style.PREP, Level.STARTER))
                .extracting(Topic::id)
                .containsExactly("a", "b");
        assertThat(catalog.pool(Style.STAR, Level.STARTER))
                .extracting(Topic::id)
                .containsExactly("d");
        assertThat(catalog.pool(Style.STAR, Level.UPPER_INTERMEDIATE)).isEmpty();
    }

    @Test
    void aRetiredTopicIsNotInThePoolButGetStillFindsIt() {
        Topic gone = retired(prep("old", Level.STARTER, 3));
        TopicCatalog catalog = catalog(List.of(prep("a", Level.STARTER, 1), gone));

        assertThat(catalog.pool(Style.PREP, Level.STARTER))
                .extracting(Topic::id)
                .containsExactly("a");
        assertThat(catalog.get("old")).contains(gone);
        assertThat(catalog.get("a")).isPresent();
    }

    @Test
    void getOfAnUnknownIdIsEmpty() {
        assertThat(catalog(List.of(prep("a", Level.STARTER, 1))).get("nope")).isEmpty();
    }

    @Test
    void poolListsTopicsInIdOrderWhateverOrderTheyWereLoaded() {
        TopicCatalog catalog =
                catalog(List.of(prep("c", Level.STARTER, 1), prep("a", Level.STARTER, 1), prep("b", Level.STARTER, 1)));

        assertThat(catalog.pool(Style.PREP, Level.STARTER))
                .extracting(Topic::id)
                .containsExactly("a", "b", "c");
    }

    @Test
    void theVersionIsTwelveHexCharactersAndIgnoresLoadingOrder() {
        List<Topic> topics = List.of(prep("a", Level.STARTER, 1), prep("b", Level.STARTER, 2));
        List<Topic> reversed = List.of(topics.get(1), topics.get(0));

        assertThat(catalog(topics).version()).matches("[0-9a-f]{12}");
        assertThat(catalog(reversed).version()).isEqualTo(catalog(topics).version());
    }

    @Test
    void theVersionChangesWhenATopicChangesOrIsRetiredOrAdded() {
        Topic a = prep("a", Level.STARTER, 1);
        String base = catalog(List.of(a)).version();

        assertThat(catalog(List.of(withText(a, "A new question?"))).version()).isNotEqualTo(base);
        assertThat(catalog(List.of(retired(a))).version()).isNotEqualTo(base);
        assertThat(catalog(List.of(a, prep("b", Level.STARTER, 2))).version()).isNotEqualTo(base);
    }
}
