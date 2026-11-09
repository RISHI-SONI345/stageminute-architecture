package app.stageminute.topics;

import static app.stageminute.topics.Topics.catalog;
import static app.stageminute.topics.Topics.ladder;
import static app.stageminute.topics.Topics.prep;
import static app.stageminute.topics.Topics.retired;
import static app.stageminute.topics.Topics.seed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * F-014 and AD-102: the pure pick. The target difficulty rises from 1 to 5 over 35 completed sessions; the first spin
 * ever takes the seed; an excluded id never comes back; an exhausted pool falls back to the topic excluded longest
 * ago. Expected values are worked out by hand from "1 + floor(4 x completed / 35)", not read from the code.
 */
class TopicPickTest {

    /** Always takes the first candidate. */
    private static final RandomGenerator FIRST = new FixedRandom(0);

    /** Always takes the last candidate. */
    private static final RandomGenerator LAST = new FixedRandom(Integer.MAX_VALUE);

    private static final class FixedRandom implements RandomGenerator {
        private final int index;

        FixedRandom(int index) {
            this.index = index;
        }

        @Override
        public long nextLong() {
            return index;
        }

        @Override
        public int nextInt(int bound) {
            return Math.min(index, bound - 1);
        }
    }

    private static String pickId(TopicCatalog catalog, int completed, List<String> exclude, RandomGenerator random) {
        return catalog.pick(Style.PREP, Level.STARTER, completed, exclude, random)
                .map(Topic::id)
                .orElse("none");
    }

    @ParameterizedTest(name = "{0} completed sessions: target difficulty gives {1}")
    @CsvSource({
        "0,   p1", "8,   p1", "9,   p2", "17,  p2", "18,  p3", "26,  p3", "27,  p4", "34,  p4", "35,  p5", "36,  p5",
        "500, p5",
    })
    void theTargetDifficultyRisesFromOneToFiveOver35Sessions(int completed, String expectedId) {
        TopicCatalog catalog = catalog(ladder(Level.STARTER));

        assertThat(pickId(catalog, completed, List.of(), FIRST)).isEqualTo(expectedId);
    }

    @Test
    void theRampLengthIsASettingNotALiteral() {
        TopicCatalog ten = TopicCatalog.of(ladder(Level.STARTER), new TopicSettings(10, 20));

        assertThat(pickId(ten, 3, List.of(), FIRST)).isEqualTo("p2"); // 1 + floor(4 x 3 / 10)
        assertThat(pickId(ten, 10, List.of(), FIRST)).isEqualTo("p5");
    }

    @Test
    void theFirstSpinEverTakesTheSeedEvenWhenItIsNotDifficultyOne() {
        List<Topic> topics = new ArrayList<>(ladder(Level.STARTER));
        topics.add(seed(prep("s", Level.STARTER, 2)));
        TopicCatalog catalog = catalog(topics);

        assertThat(pickId(catalog, 0, List.of(), FIRST)).isEqualTo("s");
        assertThat(pickId(catalog, 0, List.of(), LAST)).isEqualTo("s");
        assertThat(pickId(catalog, 1, List.of(), FIRST)).isEqualTo("p1"); // only completed 0 is special
    }

    @Test
    void anExcludedSeedIsNotReturned() {
        List<Topic> topics = new ArrayList<>(ladder(Level.STARTER));
        topics.add(seed(prep("s", Level.STARTER, 1)));
        TopicCatalog catalog = catalog(topics);

        assertThat(pickId(catalog, 0, List.of("s"), FIRST)).isEqualTo("p1");
    }

    @Test
    void aRetiredSeedIsNeverReturned() {
        List<Topic> topics = new ArrayList<>(ladder(Level.STARTER));
        topics.add(retired(seed(prep("s", Level.STARTER, 1))));
        TopicCatalog catalog = catalog(topics);

        assertThat(pickId(catalog, 0, List.of(), FIRST)).isEqualTo("p1");
    }

    @Test
    void aCellWithoutASeedTakesTheEasiestTopicOnTheFirstSpin() {
        TopicCatalog catalog = catalog(ladder(Level.INTERMEDIATE));

        assertThat(catalog.pick(Style.PREP, Level.INTERMEDIATE, 0, List.of(), FIRST)
                        .map(Topic::id))
                .contains("p1");
    }

    @Test
    void aRetiredTopicIsNeverPicked() {
        List<Topic> topics = new ArrayList<>(ladder(Level.STARTER));
        topics.set(0, retired(topics.get(0)));
        TopicCatalog catalog = catalog(topics);

        assertThat(pickId(catalog, 0, List.of(), FIRST)).isEqualTo("p2");
    }

    @Test
    void anExcludedIdIsNeverReturnedWhileAnotherTopicIsLeft() {
        TopicCatalog catalog = catalog(ladder(Level.STARTER));

        assertThat(pickId(catalog, 0, List.of("p1", "p2"), FIRST)).isEqualTo("p3");
        assertThat(pickId(catalog, 40, List.of("p5", "p4"), FIRST)).isEqualTo("p3");
    }

    @Test
    void idsOfOtherCellsOrUnknownTopicsInExcludeAreIgnored() {
        TopicCatalog catalog = catalog(ladder(Level.STARTER));

        assertThat(pickId(catalog, 0, List.of("x1", "zzz"), FIRST)).isEqualTo("p1");
    }

    @Test
    void whenNothingMatchesTheTargetTheNearestDifficultyWinsAndATieGoesToTheEasier() {
        TopicCatalog catalog = catalog(List.of(prep("e1", Level.STARTER, 1), prep("e3", Level.STARTER, 3)));

        // 9 completed sessions: target 2, both are one step away
        assertThat(pickId(catalog, 9, List.of(), FIRST)).isEqualTo("e1");
        // 35 completed sessions: target 5, 3 is nearer than 1
        assertThat(pickId(catalog, 35, List.of(), FIRST)).isEqualTo("e3");
    }

    @Test
    void topicsOfTheSameBestDifficultyAreChosenWithTheRandomItIsGiven() {
        TopicCatalog catalog =
                catalog(List.of(prep("a", Level.STARTER, 1), prep("b", Level.STARTER, 1), prep("c", Level.STARTER, 1)));

        assertThat(pickId(catalog, 1, List.of(), FIRST)).isEqualTo("a");
        assertThat(pickId(catalog, 1, List.of(), LAST)).isEqualTo("c");
    }

    @Test
    void anExhaustedPoolFallsBackToTheTopicExcludedLongestAgo() {
        TopicCatalog catalog = catalog(ladder(Level.STARTER));
        // oldest first: p3 was excluded longest ago; "x1" belongs to another cell and does not count
        List<String> exclude = List.of("x1", "p3", "p1", "p2", "p5", "p4");

        assertThat(pickId(catalog, 0, exclude, FIRST)).isEqualTo("p3");
        assertThat(pickId(catalog, 20, exclude, LAST)).isEqualTo("p3");
    }

    @Test
    void anEmptyPoolGivesNoTopicInsteadOfAnInventedOne() {
        TopicCatalog catalog = catalog(ladder(Level.STARTER));

        assertThat(catalog.pick(Style.STAR, Level.STARTER, 0, List.of(), FIRST)).isEmpty();
        assertThat(catalog(List.of()).pick(Style.PREP, Level.STARTER, 0, List.of(), FIRST))
                .isEmpty();
    }

    @Test
    void aNegativeSessionCountIsRefused() {
        TopicCatalog catalog = catalog(ladder(Level.STARTER));

        assertThatThrownBy(() -> catalog.pick(Style.PREP, Level.STARTER, -1, List.of(), FIRST))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
