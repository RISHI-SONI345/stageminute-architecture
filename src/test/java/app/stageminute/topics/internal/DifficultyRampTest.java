package app.stageminute.topics.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The ramp of the topic picker: {@code 1 + floor(4 x completed / rampSessions)}, never above 5 (ticket #10). */
class DifficultyRampTest {

    @Test
    void followsTheLaunchValueOf35Sessions() {
        assertThat(DifficultyRamp.target(0, 35)).isEqualTo(1);
        assertThat(DifficultyRamp.target(8, 35)).isEqualTo(1);
        assertThat(DifficultyRamp.target(9, 35)).isEqualTo(2);
        assertThat(DifficultyRamp.target(18, 35)).isEqualTo(3);
        assertThat(DifficultyRamp.target(27, 35)).isEqualTo(4);
        assertThat(DifficultyRamp.target(35, 35)).isEqualTo(5);
    }

    @Test
    void neverGoesAboveTheHardestLevel() {
        assertThat(DifficultyRamp.target(1000, 35)).isEqualTo(5);
    }

    @Test
    void aRampOfZeroSessionsMeansNoRampInsteadOfADivisionByZero() {
        assertThat(DifficultyRamp.target(0, 0)).isEqualTo(1 + 0);
        assertThat(DifficultyRamp.target(3, 0)).isEqualTo(5);
    }
}
