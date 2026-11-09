package app.stageminute.analysis.score;

import static app.stageminute.analysis.score.StepStatus.MISSING;
import static app.stageminute.analysis.score.StepStatus.STRONG;
import static app.stageminute.analysis.score.StepStatus.WEAK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 03 F-030, Structure 45. Expected values are worked by hand from the weights in the requirement (PREP 13.5 / 9 /
 * 11.25 / 11.25, STAR 9 / 6.75 / 15.75 / 13.5; strong 1.0, weak 0.5, missing 0) and rounded half up.
 */
class StructureScoreTest {

    static final StructureSettings DEFAULT = StructureSettings.LAUNCH_DEFAULT;

    static Stream<Arguments> takes() {
        return Stream.of(
                // The worked check in F-030: Point strong, Reason strong, Example weak, Closing missing
                // = 13.5 + 9 + 5.625 + 0 = 28.125, so 28 of 45.
                Arguments.of(Style.PREP, List.of(STRONG, STRONG, WEAK, MISSING), 28),
                Arguments.of(Style.PREP, List.of(STRONG, STRONG, STRONG, STRONG), 45),
                Arguments.of(Style.PREP, List.of(MISSING, MISSING, MISSING, MISSING), 0),
                // all weak is exactly 22.5: rounds half up to 23
                Arguments.of(Style.PREP, List.of(WEAK, WEAK, WEAK, WEAK), 23),
                // one step alone: 13.5 rounds up to 14, 9 stays 9
                Arguments.of(Style.PREP, List.of(STRONG, MISSING, MISSING, MISSING), 14),
                Arguments.of(Style.PREP, List.of(MISSING, STRONG, MISSING, MISSING), 9),
                // STAR has its own weights
                Arguments.of(Style.STAR, List.of(STRONG, STRONG, STRONG, STRONG), 45),
                Arguments.of(Style.STAR, List.of(WEAK, WEAK, WEAK, WEAK), 23),
                Arguments.of(Style.STAR, List.of(STRONG, MISSING, MISSING, MISSING), 9),
                // Task weak is 3.375, rounds down to 3
                Arguments.of(Style.STAR, List.of(MISSING, WEAK, MISSING, MISSING), 3),
                // Action strong + Result weak = 15.75 + 6.75 = 22.5, rounds half up to 23
                Arguments.of(Style.STAR, List.of(MISSING, MISSING, STRONG, WEAK), 23),
                // Action weak + Result strong = 7.875 + 13.5 = 21.375, rounds down to 21
                Arguments.of(Style.STAR, List.of(MISSING, MISSING, WEAK, STRONG), 21));
    }

    @ParameterizedTest(name = "{0} {1} earns {2}")
    @MethodSource("takes")
    void stepsEarnTheirWeightTimesTheirFactor(Style style, List<StepStatus> steps, int expected) {
        assertThat(StructureScore.points(style, steps, 4, DEFAULT)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "relevance {0}: all strong earns {1}")
    @CsvSource({"1, 22", "2, 45", "3, 45", "4, 45"})
    void relevanceLevelOneCapsStructureAt22(int relevance, int expected) {
        List<StepStatus> allStrong = List.of(STRONG, STRONG, STRONG, STRONG);

        assertThat(StructureScore.points(Style.PREP, allStrong, relevance, DEFAULT))
                .isEqualTo(expected);
    }

    @Test
    void theCapNeverRaisesALowerScore() {
        // Point alone is 14, under the cap of 22, so relevance level 1 leaves it as it is.
        assertThat(StructureScore.points(Style.PREP, List.of(STRONG, MISSING, MISSING, MISSING), 1, DEFAULT))
                .isEqualTo(14);
    }

    @Test
    void aTakeNeedsExactlyFourSteps() {
        assertThatThrownBy(() -> StructureScore.points(Style.PREP, List.of(STRONG, STRONG), 4, DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "relevance {0} is rejected")
    @ValueSource(ints = {0, 5, -1})
    void relevanceIsLevelOneToFour(int relevance) {
        List<StepStatus> steps = List.of(STRONG, STRONG, STRONG, STRONG);

        assertThatThrownBy(() -> StructureScore.points(Style.PREP, steps, relevance, DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void settingsReadAsInTheRequirement() {
        assertThat(DEFAULT.prep().asList()).containsExactly(1350, 900, 1125, 1125);
        assertThat(DEFAULT.star().asList()).containsExactly(900, 675, 1575, 1350);
        assertThat(DEFAULT.prep().totalCp()).isEqualTo(4500);
        assertThat(DEFAULT.star().totalCp()).isEqualTo(4500);
        assertThat(DEFAULT.relevanceOneCap()).isEqualTo(22);
    }

    @Test
    void badSettingsAreRejected() {
        StepWeights w = DEFAULT.prep();

        assertThatThrownBy(() -> new StructureSettings(w, w, 100, 100, 22))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StructureSettings(w, w, 100, 50, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StepWeights(0, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
