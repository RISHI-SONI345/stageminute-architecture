package app.stageminute.analysis.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pace word on Delivery (step3/CONFLICTS.md C-65, D-88 in step3/ROWS-TO-APPLY-1.8.md): built from the 10 s pace
 * windows and the take's own band. Launch limits: steady when at least 80% of the windows are inside the band; a bit
 * fast or a bit slow when at least 75% of the windows outside it lean the same way; uneven otherwise. It gives no
 * points.
 */
class PaceWordsTest {

    private static final PaceBand BAND = PaceBand.LAUNCH_DEFAULT; // 85 to 240
    private static final PaceWordSettings LIMITS = PaceWordSettings.LAUNCH_DEFAULT;

    @Test
    void everyWindowInsideTheBandIsSteady() {
        assertThat(PaceWords.of(List.of(140, 150, 160, 130, 145), BAND, LIMITS)).contains(PaceWord.STEADY);
    }

    @Test
    void fourOfFiveWindowsInsideIsStillSteady() {
        // 80% inside, the edge of the steady limit
        assertThat(PaceWords.of(List.of(140, 150, 260, 130, 145), BAND, LIMITS)).contains(PaceWord.STEADY);
    }

    @Test
    void mostlyAboveTheBandIsABitFast() {
        assertThat(PaceWords.of(List.of(250, 260, 150, 270, 245), BAND, LIMITS)).contains(PaceWord.A_BIT_FAST);
    }

    @Test
    void mostlyBelowTheBandIsABitSlow() {
        assertThat(PaceWords.of(List.of(70, 80, 150, 60, 140), BAND, LIMITS)).contains(PaceWord.A_BIT_SLOW);
    }

    @Test
    void outsideOnBothSidesIsUneven() {
        assertThat(PaceWords.of(List.of(70, 260, 150, 60, 250), BAND, LIMITS)).contains(PaceWord.UNEVEN);
    }

    @Test
    void theBandEdgesCountAsInside() {
        assertThat(PaceWords.of(List.of(85, 240, 85, 240, 85), BAND, LIMITS)).contains(PaceWord.STEADY);
    }

    @Test
    void thePersonalBandIsTheOneUsed() {
        // inside the launch band, but above a personal band of 120 to 170
        PaceBand personal = new PaceBand(120, 170);
        assertThat(PaceWords.of(List.of(190, 200, 150, 210, 195), personal, LIMITS))
                .contains(PaceWord.A_BIT_FAST);
    }

    @Test
    void theLimitsComeFromTheSettings() {
        List<Integer> windows = List.of(140, 150, 260, 130, 145); // 80% inside
        assertThat(PaceWords.of(windows, BAND, new PaceWordSettings(90, 75))).contains(PaceWord.A_BIT_FAST);
        List<Integer> mixed = List.of(260, 260, 70, 150, 150); // two of three outside are fast
        assertThat(PaceWords.of(mixed, BAND, new PaceWordSettings(80, 60))).contains(PaceWord.A_BIT_FAST);
        assertThat(PaceWords.of(mixed, BAND, new PaceWordSettings(80, 75))).contains(PaceWord.UNEVEN);
    }

    @Test
    void aTakeWithNoWindowsHasNoWord() {
        assertThat(PaceWords.of(List.of(), BAND, LIMITS)).isEmpty();
    }

    @Test
    void theWireWordsAreTheFourDecidedOnes() {
        assertThat(List.of(PaceWord.values()).stream().map(PaceWord::wire))
                .containsExactly("steady", "a_bit_fast", "a_bit_slow", "uneven");
    }

    @Test
    void limitsOutsideZeroToAHundredAreRefused() {
        assertThatThrownBy(() -> new PaceWordSettings(0, 75)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaceWordSettings(80, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaceWordSettings(80, 50)).isInstanceOf(IllegalArgumentException.class);
    }
}
