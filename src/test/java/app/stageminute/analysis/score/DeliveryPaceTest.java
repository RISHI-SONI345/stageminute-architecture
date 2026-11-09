package app.stageminute.analysis.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 03 F-030, Delivery 20 = flow 10 + pace 10. Pace = share of 10 s windows inside the band x 10; the band is 85 to 240
 * words a minute at launch. The part is rounded once, half up, after flow and pace are added exactly. Expected values
 * are worked by hand.
 */
class DeliveryPaceTest {

    static final DeliverySettings DEFAULT = DeliverySettings.LAUNCH_DEFAULT;
    static final PaceBand BAND = PaceBand.LAUNCH_DEFAULT;

    /** A clean take (no pauses, no fillers) of 60 s with the given windows. */
    private static int cleanTake(List<Integer> windowWpm) {
        return DeliveryScore.points(new DeliveryFacts(0, 0, 0, 60_000, windowWpm), BAND, DEFAULT);
    }

    /** {@code inBand} windows at 150 wpm followed by {@code outOfBand} windows at 300 wpm. */
    private static List<Integer> windows(int inBand, int outOfBand) {
        return IntStream.range(0, inBand + outOfBand)
                .mapToObj(i -> i < inBand ? 150 : 300)
                .toList();
    }

    @ParameterizedTest(name = "{0} of {1} windows in band, clean flow: Delivery {2}")
    @CsvSource({
        // pace 10: 10 + 10
        "6, 6, 20",
        // pace 0: 10 + 0
        "0, 6, 10",
        // half: pace 5 -> 15
        "3, 6, 15",
        // 1 of 3: pace 3.33 -> 13.33 -> 13;  2 of 3: pace 6.67 -> 16.67 -> 17
        "1, 3, 13",
        "2, 3, 17",
        // 5 of 8: pace 6.25 -> 16.25 -> 16
        "5, 8, 16",
        // 1 of 8: pace 1.25 -> 11.25 -> 11;  3 of 8: pace 3.75 -> 13.75 -> 14
        "1, 8, 11",
        "3, 8, 14"
    })
    void paceIsTheShareOfWindowsInsideTheBand(int inBand, int total, int expected) {
        assertThat(cleanTake(windows(inBand, total - inBand))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "a window at {0} wpm is in band: {1}")
    @CsvSource({"84, false", "85, true", "150, true", "240, true", "241, false", "0, false"})
    void theBandEdgesCountAsInside(int wpm, boolean inside) {
        // one window: pace is 10 if inside, 0 if not -> Delivery 20 or 10
        assertThat(cleanTake(List.of(wpm))).isEqualTo(inside ? 20 : 10);
    }

    @Test
    void flowAndPaceAreAddedExactlyBeforeTheOneRounding() {
        // one pause: flow 7.3. 13 of 25 windows in band: pace 5.2. 7.3 + 5.2 = 12.5 exactly, so half up gives 13.
        DeliveryFacts facts = new DeliveryFacts(1, 0, 0, 60_000, windows(13, 12));

        assertThat(DeliveryScore.points(facts, BAND, DEFAULT)).isEqualTo(13);
    }

    @Test
    void pausesAndFillersLowerTheWholePart() {
        // flow 4 (pause + cluster + fast fillers), pace 10 -> 14
        DeliveryFacts facts = new DeliveryFacts(1, 1, 8, 60_000, windows(6, 0));

        assertThat(DeliveryScore.points(facts, BAND, DEFAULT)).isEqualTo(14);
    }

    @Test
    void aPersonalBandMovesTheEdges() {
        // 100 wpm is out of 85..240? No, it is in. Use a personal band of 140..190: 100 is out, 150 is in.
        PaceBand personal = new PaceBand(140, 190);
        DeliveryFacts facts = new DeliveryFacts(0, 0, 0, 60_000, List.of(100, 150));

        assertThat(DeliveryScore.points(facts, personal, DEFAULT)).isEqualTo(15);
    }

    @Test
    void aTakeNeedsAtLeastOneWindow() {
        assertThatThrownBy(() -> new DeliveryFacts(0, 0, 0, 60_000, Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBandNeedsASensibleRange() {
        assertThatThrownBy(() -> new PaceBand(0, 240)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaceBand(200, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThat(PaceBand.LAUNCH_DEFAULT).isEqualTo(new PaceBand(85, 240));
    }
}
