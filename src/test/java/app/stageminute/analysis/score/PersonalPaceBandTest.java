package app.stageminute.analysis.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 03 F-027 (v1.7), personal pace band: computed from past takes only. Each scored past take gives its average pace. The
 * band is the median of the last 10 (the mean of the two middle values when the count is even) plus or minus 25, from
 * the 5th take on, and never wider than 85 to 240. Expected bands are worked by hand.
 */
class PersonalPaceBandTest {

    static final PersonalBandSettings DEFAULT = PersonalBandSettings.LAUNCH_DEFAULT;

    private static PaceBand choose(Integer... pastTakeWpm) {
        return PersonalPaceBand.choose(List.of(pastTakeWpm), DEFAULT);
    }

    @Test
    void untilThereAreFivePastTakesTheLaunchBandApplies() {
        assertThat(choose()).isEqualTo(PaceBand.LAUNCH_DEFAULT);
        assertThat(choose(150, 150, 150, 150)).isEqualTo(PaceBand.LAUNCH_DEFAULT);
    }

    @Test
    void fromTheFifthPastTakeTheBandIsTheMedianPlusOrMinus25() {
        // median 120 -> 95..145
        assertThat(choose(100, 110, 120, 130, 140)).isEqualTo(new PaceBand(95, 145));
        // the order they were given in does not change the median
        assertThat(choose(140, 100, 130, 120, 110)).isEqualTo(new PaceBand(95, 145));
    }

    @Test
    void onlyTheLastTenTakesCount() {
        // two old fast takes (300) then ten at 150: the last ten are all 150 -> 125..175
        List<Integer> past = new java.util.ArrayList<>(List.of(300, 300));
        past.addAll(Collections.nCopies(10, 150));

        assertThat(PersonalPaceBand.choose(past, DEFAULT)).isEqualTo(new PaceBand(125, 175));
    }

    @Test
    void anEvenCountUsesTheMeanOfTheTwoMiddleValuesRoundedHalfUp() {
        // 100 110 120 130 140 150: middle 120 and 130 -> 125 -> 100..150
        assertThat(choose(100, 110, 120, 130, 140, 150)).isEqualTo(new PaceBand(100, 150));
        // middle 150 and 151 -> 150.5 -> 151 -> 126..176
        assertThat(choose(100, 100, 150, 151, 200, 200)).isEqualTo(new PaceBand(126, 176));
    }

    @Test
    void theBandIsNeverWiderThanTheLaunchBand() {
        // median 235 -> 210..260, cut to 210..240
        assertThat(choose(235, 235, 235, 235, 235)).isEqualTo(new PaceBand(210, 240));
        // median 100 -> 75..125, cut to 85..125
        assertThat(choose(100, 100, 100, 100, 100)).isEqualTo(new PaceBand(85, 125));
    }

    @Test
    void aMedianOutsideTheLaunchBandIsBroughtInsideFirst() {
        // median 60 is below 85: treated as 85 -> 60..110, cut to 85..110
        assertThat(choose(60, 60, 60, 60, 60)).isEqualTo(new PaceBand(85, 110));
        // median 300 is above 240: treated as 240 -> 215..265, cut to 215..240
        assertThat(choose(300, 300, 300, 300, 300)).isEqualTo(new PaceBand(215, 240));
    }

    @Test
    void impossibleInputIsRejected() {
        assertThatThrownBy(() -> choose(100, 100, 100, 100, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PersonalBandSettings(0, 10, 25, PaceBand.LAUNCH_DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PersonalBandSettings(5, 4, 25, PaceBand.LAUNCH_DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PersonalBandSettings(5, 10, 0, PaceBand.LAUNCH_DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void settingsReadAsInTheRequirement() {
        assertThat(DEFAULT.minTakes()).isEqualTo(5);
        assertThat(DEFAULT.lastTakes()).isEqualTo(10);
        assertThat(DEFAULT.halfWidthWpm()).isEqualTo(25);
        assertThat(DEFAULT.limits()).isEqualTo(new PaceBand(85, 240));
    }
}
