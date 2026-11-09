package app.stageminute.analysis.spend;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.platform.SpendDecision;
import app.stageminute.platform.SpendGate.Tier;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The throttle decision (03 F-087, D-58): a pure function of the day's spend, the budget and the plan. The numbers
 * below are the requirement values: USD 15 a day, free paused at 100 %, Pro ceiling 40 to 10 at 150 %, alert at 70 %.
 */
class ThrottlePolicyTest {

    private static final long USD = 1_000_000L;
    private static final ThrottlePolicy.Levels LEVELS = new ThrottlePolicy.Levels(15 * USD, 70, 100, 150, 10);

    private static SpendDecision decide(long confirmedUsd, long reservedMicros, Tier tier) {
        return ThrottlePolicy.decide(confirmedUsd * USD, reservedMicros, LEVELS, tier);
    }

    @Test
    void everyoneIsAllowedBelowTheFreeLevel() {
        assertThat(decide(0, 0, Tier.FREE)).isEqualTo(SpendDecision.ALLOW);
        assertThat(decide(14, 999_999, Tier.FREE)).isEqualTo(SpendDecision.ALLOW);
        assertThat(decide(14, 999_999, Tier.PRO)).isEqualTo(SpendDecision.ALLOW);
    }

    @Test
    void freeUsersArePausedAtExactlyTheBudgetAndProKeepsWorking() {
        assertThat(decide(15, 0, Tier.FREE)).isEqualTo(SpendDecision.FREE_PAUSED);
        assertThat(decide(15, 0, Tier.PRO)).isEqualTo(SpendDecision.ALLOW);
        assertThat(decide(22, 0, Tier.PRO)).isEqualTo(SpendDecision.ALLOW); // 146 %: Pro still has the full ceiling
    }

    @Test
    void reservedSpendCountsLikeConfirmedSpend() {
        assertThat(ThrottlePolicy.decide(14 * USD, USD, LEVELS, Tier.FREE)).isEqualTo(SpendDecision.FREE_PAUSED);
    }

    @Test
    void theProCeilingDropsToTenAtOneAndAHalfTimesTheBudget() {
        long justUnder = 22 * USD + 499_999;
        assertThat(ThrottlePolicy.decide(justUnder, 0, LEVELS, Tier.PRO)).isEqualTo(SpendDecision.ALLOW);
        assertThat(ThrottlePolicy.decide(22 * USD + 500_000, 0, LEVELS, Tier.PRO))
                .isEqualTo(SpendDecision.proReduced(10));
        assertThat(decide(40, 0, Tier.FREE)).isEqualTo(SpendDecision.FREE_PAUSED); // free stays paused above 100 %
    }

    @Test
    void aBudgetOfZeroOrLessPausesEveryone() {
        ThrottlePolicy.Levels none = new ThrottlePolicy.Levels(0, 70, 100, 150, 10);
        assertThat(ThrottlePolicy.decide(0, 0, none, Tier.FREE)).isEqualTo(SpendDecision.FREE_PAUSED);
        assertThat(ThrottlePolicy.decide(0, 0, none, Tier.PRO)).isEqualTo(SpendDecision.proReduced(10));
    }

    @Test
    void theAlertLevelsReachedAreListedLowestFirst() {
        assertThat(ThrottlePolicy.alertsReached(10 * USD, LEVELS)).isEmpty();
        assertThat(ThrottlePolicy.alertsReached(10 * USD + 500_000, LEVELS))
                .containsExactly(ThrottlePolicy.AlertLevel.ALERT);
        assertThat(ThrottlePolicy.alertsReached(15 * USD, LEVELS))
                .containsExactly(ThrottlePolicy.AlertLevel.ALERT, ThrottlePolicy.AlertLevel.FREE_THROTTLE);
        assertThat(ThrottlePolicy.alertsReached(23 * USD, LEVELS))
                .isEqualTo(List.of(
                        ThrottlePolicy.AlertLevel.ALERT,
                        ThrottlePolicy.AlertLevel.FREE_THROTTLE,
                        ThrottlePolicy.AlertLevel.PRO_THROTTLE));
    }

    @Test
    void thePercentOfTheBudgetIsWholeAndNeverDividesByZero() {
        assertThat(ThrottlePolicy.percentOf(7_500_000, LEVELS)).isEqualTo(50);
        assertThat(ThrottlePolicy.percentOf(1, new ThrottlePolicy.Levels(0, 70, 100, 150, 10)))
                .isEqualTo(Integer.MAX_VALUE);
    }
}
