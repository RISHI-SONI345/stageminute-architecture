package app.stageminute.analysis.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@code delta.direction} (02b F-045 "About the same ... inside the 7-point noise band"; C-63): the server decides it,
 * the app only shows the word. A change of fewer than 7 points either way is the same.
 */
class ScoreDeltaTest {

    private static final int BAND = 7;

    @Test
    void sevenOrMorePointsUpIsUp() {
        assertThat(ScoreDelta.direction(78, 85, BAND)).isEqualTo(ScoreDelta.Direction.UP);
    }

    @Test
    void sevenOrMorePointsDownIsDown() {
        assertThat(ScoreDelta.direction(85, 78, BAND)).isEqualTo(ScoreDelta.Direction.DOWN);
    }

    @Test
    void lessThanSevenPointsEitherWayIsTheSame() {
        assertThat(ScoreDelta.direction(78, 84, BAND)).isEqualTo(ScoreDelta.Direction.SAME);
        assertThat(ScoreDelta.direction(84, 78, BAND)).isEqualTo(ScoreDelta.Direction.SAME);
        assertThat(ScoreDelta.direction(78, 78, BAND)).isEqualTo(ScoreDelta.Direction.SAME);
    }

    @Test
    void theBandComesFromTheCaller() {
        assertThat(ScoreDelta.direction(78, 81, 3)).isEqualTo(ScoreDelta.Direction.UP);
    }

    @Test
    void aNegativeBandIsRefused() {
        assertThatThrownBy(() -> ScoreDelta.direction(1, 2, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
