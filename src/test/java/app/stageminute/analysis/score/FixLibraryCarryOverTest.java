package app.stageminute.analysis.score;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * 03 F-032: "on attempts 2 and 3 the previous fix stays unless its re-measure rule says Done", and F-033: the previous
 * facts come from the server's own {@code facts_record}. Expected values are worked by hand from those two lines.
 */
class FixLibraryCarryOverTest {

    static final FixSettings SETTINGS = FixSettings.LAUNCH_DEFAULT;

    private static FixSelection choose(
            FixChoice previousFix, UnaryOperator<Takes> previous, UnaryOperator<Takes> current) {
        PreviousTake before =
                new PreviousTake(previousFix, previous.apply(Takes.strong()).build());
        return FixLibrary.choose(current.apply(Takes.strong()).build(), Optional.of(before), SETTINGS);
    }

    private static FixChoice fix(FixId id, int step) {
        return new FixChoice(id, step, false, false);
    }

    @Test
    void theFirstAttemptHasNoFocusCheck() {
        FixSelection selection =
                FixLibrary.choose(Takes.strong().step(1, StepStatus.MISSING).build(), Optional.empty(), SETTINGS);

        assertThat(selection.fix().id()).isEqualTo(FixId.ADD_REASON);
        assertThat(selection.fix().carriedOver()).isFalse();
        assertThat(selection.focusCheck()).isEmpty();
    }

    @Test
    void aFixThatIsNotYetDoneStaysEvenWhenAHigherFixNowFires() {
        // previous: example missing -> add_example. Now the example is still missing and the point is missing too;
        // a fresh pick would be open_point (P2), but the previous fix stays.
        FixSelection selection = choose(
                fix(FixId.ADD_EXAMPLE, 2),
                t -> t.step(2, StepStatus.MISSING),
                t -> t.step(2, StepStatus.MISSING).step(0, StepStatus.MISSING));

        assertThat(selection.fix().id()).isEqualTo(FixId.ADD_EXAMPLE);
        assertThat(selection.fix().carriedOver()).isTrue();
        assertThat(selection.focusCheck().orElseThrow().result()).isEqualTo(FocusResult.NOT_YET);
    }

    @Test
    void aFixThatIsBetterStays() {
        FixSelection selection = choose(
                fix(FixId.ADD_EXAMPLE, 2),
                t -> t.step(2, StepStatus.MISSING),
                t -> t.step(2, StepStatus.WEAK).step(0, StepStatus.MISSING));

        assertThat(selection.fix().id()).isEqualTo(FixId.ADD_EXAMPLE);
        assertThat(selection.fix().carriedOver()).isTrue();
        assertThat(selection.focusCheck().orElseThrow().result()).isEqualTo(FocusResult.BETTER);
    }

    @Test
    void aFixThatIsDoneMakesWayForAFreshPick() {
        FixSelection selection = choose(
                fix(FixId.ADD_EXAMPLE, 2), t -> t.step(2, StepStatus.MISSING), t -> t.step(3, StepStatus.MISSING));

        assertThat(selection.focusCheck().orElseThrow().result()).isEqualTo(FocusResult.DONE);
        assertThat(selection.fix().id()).isEqualTo(FixId.ADD_CLOSING);
        assertThat(selection.fix().carriedOver()).isFalse();
    }

    @Test
    void whenTheLastFixIsDoneAndNothingElseFiresTheTakeGetsThePolish() {
        FixSelection selection = choose(fix(FixId.ADD_EXAMPLE, 2), t -> t.step(2, StepStatus.MISSING), t -> t);

        assertThat(selection.fix().nothingMajor()).isTrue();
        assertThat(selection.fix().carriedOver()).isFalse();
    }

    @Test
    void aCarriedFixKeepsItsTargetAndStaysCarriedOnTheThirdAttempt() {
        FixChoice carriedOnAttempt2 = new FixChoice(FixId.STRENGTHEN_STEP, 1, true, false);

        FixSelection selection =
                choose(carriedOnAttempt2, t -> t.step(1, StepStatus.WEAK), t -> t.step(1, StepStatus.WEAK));

        assertThat(selection.fix()).isEqualTo(new FixChoice(FixId.STRENGTHEN_STEP, 1, true, false));
    }

    @Test
    void aCarriedPolishStaysAPolish() {
        // Language conciseness 3 -> 3: not up by 1, so the polish stays and is still "Nothing major to fix"
        FixSelection selection = choose(
                new FixChoice(FixId.SAY_IT_SHORTER, FixChoice.NO_TARGET, false, true),
                t -> t.language(4, 3, 4),
                t -> t.language(4, 3, 4));

        assertThat(selection.fix().id()).isEqualTo(FixId.SAY_IT_SHORTER);
        assertThat(selection.fix().nothingMajor()).isTrue();
        assertThat(selection.fix().carriedOver()).isTrue();
    }

    @Test
    void aChangedStyleStartsFreshWithNoFocusCheck() {
        // EC-055: a style-bound fix is cleared when the style changes
        FixSelection selection = choose(
                fix(FixId.ADD_EXAMPLE, 2),
                t -> t.step(2, StepStatus.MISSING),
                t -> t.star().step(2, StepStatus.MISSING));

        assertThat(selection.fix().id()).isEqualTo(FixId.NAME_ACTION);
        assertThat(selection.fix().carriedOver()).isFalse();
        assertThat(selection.focusCheck()).isEmpty();
    }

    // ---- a fix that is Done is not given again on the same report (C-43) --------------------------------------------

    @Test
    void aFillerFixThatIsDoneIsNotPickedAgainEvenIfFillersAreStillOverTheLine() {
        // 20 a minute down to 14 with no cluster is Done (rate down 30%), though 14 is still over the trigger of 8
        FixSelection selection = choose(
                fix(FixId.FILLER_CLUSTER, -1),
                t -> t.durationMs(60_000).fillers(20, 3),
                t -> t.durationMs(60_000).fillers(14, 0));

        assertThat(selection.focusCheck().orElseThrow().result()).isEqualTo(FocusResult.DONE);
        assertThat(selection.fix().id()).isNotEqualTo(FixId.FILLER_CLUSTER);
        assertThat(selection.fix().nothingMajor()).isTrue();
    }

    @Test
    void aLanguageFixThatIsDoneMovesToTheNextCriterion() {
        // clarity 1 to 2 is Done; level 2 would still trigger say_it_plainly, so the next lowest criterion is used
        FixSelection selection =
                choose(fix(FixId.SAY_IT_PLAINLY, -1), t -> t.language(1, 4, 4), t -> t.language(2, 4, 4));

        assertThat(selection.focusCheck().orElseThrow().result()).isEqualTo(FocusResult.DONE);
        assertThat(selection.fix().id()).isEqualTo(FixId.SAY_IT_SHORTER);
        assertThat(selection.fix().nothingMajor()).isTrue();
    }

    @Test
    void aFixThatIsDoneStillLetsTheNextTriggerFire() {
        // the filler fix is Done but still over the line; a late start is the next trigger in the order
        FixSelection selection = choose(
                fix(FixId.FILLER_CLUSTER, -1),
                t -> t.durationMs(60_000).fillers(20, 3),
                t -> t.durationMs(60_000).fillers(14, 0).firstWordMs(5000));

        assertThat(selection.fix().id()).isEqualTo(FixId.START_SOONER);
    }

    @Test
    void aStepThatIsDoneDoesNotHideAnotherWeakStep() {
        // strengthen_step is one fix id for four steps: Point is strong now, but Example is still weak, so the
        // person is not told "nothing major"; the next fix is strengthen_step on the Example (a different step)
        FixSelection selection = choose(
                fix(FixId.STRENGTHEN_STEP, 0),
                t -> t.step(0, StepStatus.WEAK).step(2, StepStatus.WEAK),
                t -> t.step(2, StepStatus.WEAK));

        assertThat(selection.focusCheck().orElseThrow().result()).isEqualTo(FocusResult.DONE);
        assertThat(selection.fix()).isEqualTo(new FixChoice(FixId.STRENGTHEN_STEP, 2, false, false));
    }

    @Test
    void aStepThatIsDoneIsNotPickedAgainWhenNoOtherStepIsWeak() {
        FixSelection selection = choose(
                fix(FixId.STRENGTHEN_STEP, 0), t -> t.step(0, StepStatus.WEAK), t -> t.step(0, StepStatus.STRONG));

        assertThat(selection.focusCheck().orElseThrow().result()).isEqualTo(FocusResult.DONE);
        assertThat(selection.fix().id()).isNotEqualTo(FixId.STRENGTHEN_STEP);
        assertThat(selection.fix().nothingMajor()).isTrue();
    }

    @Test
    void aFixThatIsNotDoneIsStillCarriedAsBefore() {
        FixSelection selection = choose(
                fix(FixId.FILLER_CLUSTER, -1),
                t -> t.durationMs(60_000).fillers(20, 3),
                t -> t.durationMs(60_000).fillers(19, 2));

        assertThat(selection.fix().id()).isEqualTo(FixId.FILLER_CLUSTER);
        assertThat(selection.fix().carriedOver()).isTrue();
    }

    // ---- wrap_up_in_time and add_closing
    // -----------------------------------------------------------------------------------

    @Test
    void wrapUpInTimeStaysWhileTheTakeKeepsHittingTheCapWithoutAClosing() {
        FixSelection selection = choose(
                fix(FixId.WRAP_UP_IN_TIME, 3),
                t -> t.step(3, StepStatus.MISSING).hitCap(),
                t -> t.step(3, StepStatus.MISSING).hitCap());

        assertThat(selection.fix().id()).isEqualTo(FixId.WRAP_UP_IN_TIME);
        assertThat(selection.fix().carriedOver()).isTrue();
    }

    @Test
    void addClosingReturnsOnceTheTakeEndsNaturally() {
        // the take no longer hits the cap but still has no closing: the check is Better, and the fix is add_closing
        FixSelection selection = choose(
                fix(FixId.WRAP_UP_IN_TIME, 3),
                t -> t.step(3, StepStatus.MISSING).hitCap(),
                t -> t.step(3, StepStatus.MISSING));

        assertThat(selection.focusCheck().orElseThrow().result()).isEqualTo(FocusResult.BETTER);
        assertThat(selection.fix().id()).isEqualTo(FixId.ADD_CLOSING);
        assertThat(selection.fix().carriedOver()).isFalse();
    }

    @Test
    void wrapUpInTimeOutranksAddClosingAtTheCapEvenWhenAddClosingWasCarried() {
        // the previous fix was add_closing (not capped); now the take hit the cap with the closing still missing.
        // add_closing is Not yet and would stay, but wrap_up_in_time replaces it.
        FixSelection selection = choose(
                fix(FixId.ADD_CLOSING, 3),
                t -> t.step(3, StepStatus.MISSING),
                t -> t.step(3, StepStatus.MISSING).hitCap());

        assertThat(selection.fix().id()).isEqualTo(FixId.WRAP_UP_IN_TIME);
        assertThat(selection.fix().carriedOver()).isFalse();
        assertThat(selection.focusCheck().orElseThrow().result()).isEqualTo(FocusResult.NOT_YET);
    }

    @Test
    void sayResultIsReplacedTheSameWayInAStarTake() {
        FixSelection selection = choose(
                fix(FixId.SAY_RESULT, 3),
                t -> t.star().step(3, StepStatus.MISSING),
                t -> t.star().step(3, StepStatus.MISSING).hitCap());

        assertThat(selection.fix().id()).isEqualTo(FixId.WRAP_UP_IN_TIME);
    }
}
