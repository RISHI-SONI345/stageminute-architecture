package app.stageminute.analysis.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 03 F-030, Language 35: clarity, conciseness and language accuracy, each worth up to 35/3. Level 4 / 3 / 2 / 1 earns
 * 100% / 70% / 40% / 10% of that. "Rounded once, at the part": the three shares are added exactly, then rounded half
 * up. Expected values are worked by hand: points = 35 x (sum of the three percentages) / 300.
 */
class LanguageScoreTest {

    static final LanguageSettings DEFAULT = LanguageSettings.LAUNCH_DEFAULT;

    @ParameterizedTest(name = "clarity {0}, conciseness {1}, accuracy {2} earns {3}")
    @CsvSource({
        // 100% x 3 = 35
        "4, 4, 4, 35",
        // 70% x 3 = 24.5 exactly: rounds half up to 25 (rounding each criterion first would give 24)
        "3, 3, 3, 25",
        // 40% x 3 = 14
        "2, 2, 2, 14",
        // 10% x 3 = 3.5 exactly: rounds half up to 4
        "1, 1, 1, 4",
        // mixes: 270% -> 31.5 -> 32;  240% -> 28;  210% -> 24.5 -> 25;  150% -> 17.5 -> 18;  90% -> 10.5 -> 11
        "4, 4, 3, 32",
        "4, 4, 2, 28",
        "4, 3, 2, 25",
        "4, 2, 1, 18",
        "2, 2, 1, 11",
        // the order of the three criteria does not matter
        "1, 4, 2, 18",
        "2, 1, 4, 18"
    })
    void eachLevelEarnsItsShareAndTheSumIsRoundedOnce(int clarity, int conciseness, int accuracy, int expected) {
        LanguageLevels levels = new LanguageLevels(clarity, conciseness, accuracy);

        assertThat(LanguageScore.points(levels, DEFAULT)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "level {0} is rejected")
    @ValueSource(ints = {0, 5, -1})
    void aLevelIsOneToFour(int level) {
        assertThatThrownBy(() -> new LanguageLevels(level, 4, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LanguageLevels(4, level, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LanguageLevels(4, 4, level)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void settingsReadAsInTheRequirement() {
        assertThat(DEFAULT.maxPoints()).isEqualTo(35);
        assertThat(DEFAULT.level4Pct()).isEqualTo(100);
        assertThat(DEFAULT.level3Pct()).isEqualTo(70);
        assertThat(DEFAULT.level2Pct()).isEqualTo(40);
        assertThat(DEFAULT.level1Pct()).isEqualTo(10);
    }

    @Test
    void badSettingsAreRejected() {
        assertThatThrownBy(() -> new LanguageSettings(0, 100, 70, 40, 10)).isInstanceOf(IllegalArgumentException.class);
        // the levels must fall
        assertThatThrownBy(() -> new LanguageSettings(35, 100, 40, 70, 10))
                .isInstanceOf(IllegalArgumentException.class);
        // level 4 cannot earn more than the full share
        assertThatThrownBy(() -> new LanguageSettings(35, 101, 70, 40, 10))
                .isInstanceOf(IllegalArgumentException.class);
        // level 1 must earn something
        assertThatThrownBy(() -> new LanguageSettings(35, 100, 70, 40, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
