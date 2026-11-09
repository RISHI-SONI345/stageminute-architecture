package app.stageminute.trust.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

class StatsTest {

    @Test
    void meanAndSampleSdOfAKnownList() {
        // 2, 4, 4, 4, 5, 5, 7, 9: mean 5, squares of the gaps sum to 32, sample variance 32 / 7.
        List<Double> values = List.of(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0);

        assertThat(Stats.mean(values)).isEqualTo(5.0);
        assertThat(Stats.sampleSd(values)).isCloseTo(Math.sqrt(32.0 / 7.0), within(1e-12));
    }

    @Test
    void oneValueHasNoSpreadAndNoValuesAreNotANumber() {
        assertThat(Stats.sampleSd(List.of(81.0))).isEqualTo(0.0);
        assertThat(Stats.sampleSd(List.of())).isNaN();
        assertThat(Stats.mean(List.of())).isNaN();
        assertThat(Stats.range(List.of())).isNaN();
        assertThat(Stats.percentile(List.of(), 50)).isNaN();
    }

    @Test
    void rangeIsLargestMinusSmallest() {
        assertThat(Stats.range(List.of(78.0, 84.0, 81.0))).isEqualTo(6.0);
    }

    @Test
    void percentileUsesTheNearestRankRule() {
        List<Double> ten = List.of(10.0, 9.0, 8.0, 7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0);

        assertThat(Stats.percentile(ten, 50)).isEqualTo(5.0); // rank ceil(0.5 x 10) = 5
        assertThat(Stats.percentile(ten, 95)).isEqualTo(10.0); // rank ceil(9.5) = 10
        assertThat(Stats.percentile(ten, 100)).isEqualTo(10.0);
        assertThat(Stats.percentile(ten, 1)).isEqualTo(1.0);
    }

    @Test
    void percentileOutsideZeroToHundredIsRefused() {
        assertThatThrownBy(() -> Stats.percentile(List.of(1.0), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Stats.percentile(List.of(1.0), 101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pearsonOfALineIsOneAndOfAMirrorIsMinusOne() {
        assertThat(Stats.pearson(List.of(1.0, 2.0, 3.0), List.of(2.0, 4.0, 6.0)))
                .isCloseTo(1.0, within(1e-12));
        assertThat(Stats.pearson(List.of(1.0, 2.0, 3.0), List.of(6.0, 4.0, 2.0)))
                .isCloseTo(-1.0, within(1e-12));
    }

    @Test
    void pearsonIsNotANumberWhenItCannotBeSaid() {
        assertThat(Stats.pearson(List.of(1.0), List.of(1.0))).isNaN();
        assertThat(Stats.pearson(List.of(1.0, 2.0), List.of(5.0, 5.0))).isNaN();
        assertThatThrownBy(() -> Stats.pearson(List.of(1.0), List.of(1.0, 2.0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
