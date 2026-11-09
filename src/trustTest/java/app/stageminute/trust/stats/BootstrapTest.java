package app.stageminute.trust.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BootstrapTest {

    @Test
    void sameSeedGivesTheSameRange() {
        List<Double> speakers = List.of(0.9, 0.8, 1.0, 0.7, 0.95, 0.85);

        Interval first = Bootstrap.interval(speakers, Stats::mean, 42L);
        Interval second = Bootstrap.interval(speakers, Stats::mean, 42L);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void everySpeakerTheSameGivesARangeOfOnePoint() {
        Interval range = Bootstrap.interval(List.of(0.9, 0.9, 0.9), Stats::mean, 1L);

        assertThat(range.low()).isEqualTo(0.9);
        assertThat(range.high()).isEqualTo(0.9);
    }

    @Test
    void twoSpeakersZeroAndOneSpanTheWholeWay() {
        // Resampled means can only be 0, 0.5 or 1; with 2000 resamples both ends are certain to be reached.
        Interval range = Bootstrap.interval(List.of(0.0, 1.0), Stats::mean, 7L);

        assertThat(range.low()).isEqualTo(0.0);
        assertThat(range.high()).isEqualTo(1.0);
    }

    @Test
    void theRangeContainsThePointEstimateOfARealisticSet() {
        List<Double> speakers = List.of(0.9, 0.8, 1.0, 0.7, 0.95, 0.85, 0.92, 0.88, 0.79, 0.97);

        Interval range = Bootstrap.interval(speakers, Stats::mean, 3L);

        assertThat(range.low()).isLessThan(Stats.mean(speakers));
        assertThat(range.high()).isGreaterThan(Stats.mean(speakers));
    }

    @Test
    void noUnitsOrAStatisticThatIsNeverANumberGivesNoRange() {
        assertThat(Bootstrap.interval(List.<Double>of(), Stats::mean, 1L).computable())
                .isFalse();
        assertThat(Bootstrap.interval(List.of(1.0, 2.0), values -> Double.NaN, 1L)
                        .computable())
                .isFalse();
    }
}
