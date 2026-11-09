package app.stageminute.analysis.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 03 F-030, Delivery flow (out of 10): 10 - 2.7 per mid-answer pause of 5 s or more - 2 per confirmed filler cluster -
 * 1.3 if confirmed fillers are 8 or more a minute, floor 0. Values are in hundredths of a point (10 is 1000) and are
 * worked by hand from those numbers.
 */
class DeliveryFlowTest {

    static final DeliverySettings DEFAULT = DeliverySettings.LAUNCH_DEFAULT;

    @ParameterizedTest(name = "{0} pauses, {1} clusters, {2} fillers in {3} ms leaves {4}")
    @CsvSource({
        // nothing wrong
        "0, 0, 0, 60000, 1000",
        // pauses: 2.7 each
        "1, 0, 0, 60000, 730",
        "2, 0, 0, 60000, 460",
        "3, 0, 0, 60000, 190",
        // clusters: 2 each
        "0, 1, 3, 60000, 800",
        "0, 2, 6, 60000, 600",
        // the filler rate: 8 a minute is the line, 7 is under it
        "0, 0, 7, 60000, 1000",
        "0, 0, 8, 60000, 870",
        // the same rate over a shorter take: 4 in 30 s is 8 a minute, 3 in 30 s is 6
        "0, 0, 4, 30000, 870",
        "0, 0, 3, 30000, 1000",
        // all three together: 10 - 2.7 - 2 - 1.3 = 4
        "1, 1, 8, 60000, 400",
        // the floor: it never goes below 0
        "4, 0, 0, 60000, 0",
        "3, 3, 0, 60000, 0"
    })
    void flowLosesPointsForPausesClustersAndAFastFillerRate(
            int pauses, int clusters, int fillers, int durationMs, int expectedCp) {
        assertThat(DeliveryScore.flowCp(pauses, clusters, fillers, durationMs, DEFAULT))
                .isEqualTo(expectedCp);
    }

    @Test
    void aTakeNeedsALengthAndNoNegativeCounts() {
        assertThatThrownBy(() -> DeliveryScore.flowCp(0, 0, 0, 0, DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeliveryScore.flowCp(-1, 0, 0, 1000, DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeliveryScore.flowCp(0, -1, 0, 1000, DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeliveryScore.flowCp(0, 0, -1, 1000, DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void settingsReadAsInTheRequirement() {
        assertThat(DEFAULT.flowMaxCp()).isEqualTo(1000);
        assertThat(DEFAULT.paceMaxCp()).isEqualTo(1000);
        assertThat(DEFAULT.pausePenaltyCp()).isEqualTo(270);
        assertThat(DEFAULT.clusterPenaltyCp()).isEqualTo(200);
        assertThat(DEFAULT.fillerRatePenaltyCp()).isEqualTo(130);
        assertThat(DEFAULT.fillersPerMinuteLimit()).isEqualTo(8);
    }

    @Test
    void badSettingsAreRejected() {
        assertThatThrownBy(() -> new DeliverySettings(0, 1000, 270, 200, 130, 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliverySettings(1000, 0, 270, 200, 130, 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliverySettings(1000, 1000, -1, 200, 130, 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliverySettings(1000, 1000, 270, 200, 130, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
