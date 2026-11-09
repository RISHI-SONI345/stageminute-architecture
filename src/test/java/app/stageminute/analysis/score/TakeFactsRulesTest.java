package app.stageminute.analysis.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The input rules of the fact and choice records: a bad record fails where it is made, not later in a score. */
class TakeFactsRulesTest {

    private static TakeFacts with(List<StepStatus> steps, int durationMs, int wordCount) {
        return new TakeFacts(
                Style.PREP,
                steps,
                4,
                new LanguageLevels(4, 4, 4),
                wordCount,
                50_000,
                durationMs,
                800,
                0,
                0,
                0,
                0,
                20,
                false,
                List.of(140),
                PaceBand.LAUNCH_DEFAULT,
                false);
    }

    private static final List<StepStatus> FOUR_STRONG =
            List.of(StepStatus.STRONG, StepStatus.STRONG, StepStatus.STRONG, StepStatus.STRONG);

    @Test
    void aTakeNeedsFourSteps() {
        assertThatThrownBy(() -> with(List.of(StepStatus.STRONG), 58_000, 120))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTakeNeedsALength() {
        assertThatThrownBy(() -> with(FOUR_STRONG, 0, 120)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countsCannotBeNegative() {
        assertThatThrownBy(() -> with(FOUR_STRONG, 58_000, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noWordsMeansTheFirstWordTimeIsMinusOne() {
        TakeFacts silent = new TakeFacts(
                Style.PREP,
                FOUR_STRONG,
                4,
                new LanguageLevels(4, 4, 4),
                0,
                0,
                58_000,
                -1,
                0,
                0,
                0,
                0,
                0,
                false,
                List.of(),
                PaceBand.LAUNCH_DEFAULT,
                false);

        assertThat(silent.firstWordMs()).isEqualTo(-1);
        assertThat(silent.outsideBandMs(10_000)).isZero();
    }

    @Test
    void aFixChoiceTargetIsNoStepOrAStepZeroToThree() {
        assertThat(new FixChoice(FixId.NO_FREEZE, -1, false, false).target()).isEqualTo(-1);
        assertThat(new FixChoice(FixId.ADD_CLOSING, 3, false, false).target()).isEqualTo(3);
        assertThatThrownBy(() -> new FixChoice(FixId.ADD_CLOSING, 4, false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FixChoice(FixId.ADD_CLOSING, -2, false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyFixHasItsOwnCodeAsTheContentFilesWriteIt() {
        assertThat(FixId.OPEN_POINT.code()).isEqualTo("open_point");
        assertThat(FixId.WRAP_UP_IN_TIME.code()).isEqualTo("wrap_up_in_time");
        assertThat(java.util.Arrays.stream(FixId.values())
                        .map(FixId::code)
                        .distinct()
                        .count())
                .isEqualTo(FixId.values().length);
    }

    @Test
    void aStepIsZeroToThree() {
        assertThatThrownBy(() -> FixId.forMissingStep(Style.PREP, 4)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(
            value = FixId.class,
            names = {
                "OPEN_POINT",
                "SET_SCENE",
                "ADD_CLOSING",
                "SAY_RESULT",
                "ADD_EXAMPLE",
                "NAME_ACTION",
                "ADD_REASON",
                "NAME_TASK",
                "STRENGTHEN_STEP",
                "WRAP_UP_IN_TIME"
            })
    void aFixAboutOneStepNeedsToSayWhichStep(FixId fix) {
        // a stored row with no step would otherwise fail later, inside the focus check
        assertThatThrownBy(() -> new FixChoice(fix, FixChoice.NO_TARGET, false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new FixChoice(fix, 2, false, false).target()).isEqualTo(2);
    }
}
