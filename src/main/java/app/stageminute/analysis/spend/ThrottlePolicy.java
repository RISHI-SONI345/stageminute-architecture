package app.stageminute.analysis.spend;

import app.stageminute.platform.SpendDecision;
import app.stageminute.platform.SpendGate.Tier;
import java.util.ArrayList;
import java.util.List;

/**
 * The throttle decision (03 F-087, D-58), pure: the day's spend, the budget, the levels and the plan in; one of three
 * answers out. It reads no clock, no setting, no database and nothing about the person but the plan, and the plan is
 * read here only to choose the ceiling, never for a score (11 section 1, promise 3).
 *
 * <p>At or above the free level (100 %) free users are paused, ad-earned quota included ("Ads offset cost; they do not
 * bypass the ceiling"). At or above the Pro level (150 %) the Pro ceiling drops. The levels lift by themselves at the
 * next UTC day because the spend row is per UTC date.
 */
public final class ThrottlePolicy {

    private static final long PERCENT = 100L;

    private ThrottlePolicy() {}

    /**
     * The numbers the decision needs.
     *
     * @param budgetMicros the day's budget in millionths of a dollar; zero or less pauses everyone (fail closed)
     * @param alertPercent the share of the budget that raises the first alert (70)
     * @param freePercent the share at which free users are paused (100)
     * @param proPercent the share at which the Pro ceiling drops (150)
     * @param proCeiling the lowered Pro daily ceiling (10)
     */
    public record Levels(long budgetMicros, int alertPercent, int freePercent, int proPercent, int proCeiling) {}

    /** The three alert steps of F-087, lowest first. */
    public enum AlertLevel {
        ALERT,
        FREE_THROTTLE,
        PRO_THROTTLE
    }

    /** The decision for one person: {@code confirmedMicros} and {@code reservedMicros} are today's spend so far. */
    public static SpendDecision decide(long confirmedMicros, long reservedMicros, Levels levels, Tier tier) {
        long spent = confirmedMicros + reservedMicros;
        return switch (tier) {
            case FREE -> reached(spent, levels.freePercent(), levels) ? SpendDecision.FREE_PAUSED : SpendDecision.ALLOW;
            case PRO ->
                reached(spent, levels.proPercent(), levels)
                        ? SpendDecision.proReduced(levels.proCeiling())
                        : SpendDecision.ALLOW;
        };
    }

    /** Every alert step that {@code spentMicros} has reached, lowest first (for the once-a-day alerts). */
    public static List<AlertLevel> alertsReached(long spentMicros, Levels levels) {
        List<AlertLevel> reached = new ArrayList<>();
        if (reached(spentMicros, levels.alertPercent(), levels)) {
            reached.add(AlertLevel.ALERT);
        }
        if (reached(spentMicros, levels.freePercent(), levels)) {
            reached.add(AlertLevel.FREE_THROTTLE);
        }
        if (reached(spentMicros, levels.proPercent(), levels)) {
            reached.add(AlertLevel.PRO_THROTTLE);
        }
        return reached;
    }

    /** The whole percent of the budget spent; {@link Integer#MAX_VALUE} when there is no budget. */
    public static int percentOf(long spentMicros, Levels levels) {
        if (levels.budgetMicros() <= 0) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.min(Integer.MAX_VALUE, spentMicros * PERCENT / levels.budgetMicros());
    }

    /** Whole-number comparison (no rounding): spent / budget >= percent / 100. A missing budget is always reached. */
    private static boolean reached(long spentMicros, int percent, Levels levels) {
        if (levels.budgetMicros() <= 0) {
            return true;
        }
        return spentMicros * PERCENT >= levels.budgetMicros() * percent;
    }
}
