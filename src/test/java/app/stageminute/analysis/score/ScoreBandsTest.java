package app.stageminute.analysis.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** D-13 and 03 F-030 edge cases: 89/90, 84/85, 69/70, and parts at exactly 70% and 85%. */
class ScoreBandsTest {

    static final BandCutoffs DEFAULT = BandCutoffs.LAUNCH_DEFAULT;

    @ParameterizedTest(name = "total {0} reads {1}")
    @CsvSource({
        "100, EXCELLENT", "91, EXCELLENT", "90, EXCELLENT",
        "89, STRONG", "86, STRONG", "85, STRONG",
        "84, GOOD", "78, GOOD", "70, GOOD",
        "69, OPPORTUNITY", "0, OPPORTUNITY"
    })
    void overallBands(int total, Band expected) {
        assertThat(ScoreBands.overall(total, DEFAULT)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} of {1} reads {2}")
    @CsvSource({
        // Structure 45: 85% = 38.25 -> 39 is the first whole Strong; 70% = 31.5 -> 32 is the first whole Good
        "45, 45, STRONG",
        "39, 45, STRONG",
        "38, 45, GOOD",
        "32, 45, GOOD",
        "31, 45, OPPORTUNITY",
        "30, 45, OPPORTUNITY",
        // Language 35: 85% = 29.75 -> 30; 70% = 24.5 -> 25
        "32, 35, STRONG",
        "30, 35, STRONG",
        "29, 35, GOOD",
        "25, 35, GOOD",
        "24, 35, OPPORTUNITY",
        // Delivery 20: exactly 85% (17) and exactly 70% (14) land on the higher band
        "17, 20, STRONG",
        "16, 20, GOOD",
        "14, 20, GOOD",
        "13, 20, OPPORTUNITY"
    })
    void partBands(int points, int max, Band expected) {
        assertThat(ScoreBands.part(points, max, DEFAULT)).isEqualTo(expected);
    }

    @Test
    void requirementExampleReadsAsDrawn() {
        // Copy table sample (R1 template): 78 Good; Structure 30/45 Opportunity, Language 32/35 Strong, Delivery 16/20
        // Good.
        assertThat(ScoreBands.overall(78, DEFAULT)).isEqualTo(Band.GOOD);
        assertThat(ScoreBands.part(30, 45, DEFAULT)).isEqualTo(Band.OPPORTUNITY);
        assertThat(ScoreBands.part(32, 35, DEFAULT)).isEqualTo(Band.STRONG);
        assertThat(ScoreBands.part(16, 20, DEFAULT)).isEqualTo(Band.GOOD);
    }

    @Test
    void rejectsValuesOutsideTheScale() {
        assertThatThrownBy(() -> ScoreBands.overall(101, DEFAULT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoreBands.overall(-1, DEFAULT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoreBands.part(21, 20, DEFAULT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoreBands.part(1, 0, DEFAULT)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cutoffsMustFall() {
        assertThatThrownBy(() -> new BandCutoffs(85, 90, 70, 85, 70)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BandCutoffs(90, 85, 70, 70, 85)).isInstanceOf(IllegalArgumentException.class);
    }
}
