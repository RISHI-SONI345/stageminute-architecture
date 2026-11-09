package app.stageminute.practice.internal;

import app.stageminute.practice.QuotaState;
import app.stageminute.practice.Reservation;

/**
 * How many sessions a person may start in one account day, and what pays for each (03 F-049, F-052; 06
 * section 1). Free: the free allowance plus today's ad credits. Pro: the fair-use cap; ad credits add
 * nothing. Plain Java: the numbers come in from {@code Settings}, so the rule holds none.
 */
public final class AllowanceRule {

    private final int freePerDay;
    private final int proPerDay;

    public AllowanceRule(int freePerDay, int proPerDay) {
        this.freePerDay = freePerDay;
        this.proPerDay = proPerDay;
    }

    /** Today's cap on {@code daily_counters.sessions_used}. */
    public int cap(Plan plan, int adCreditsToday) {
        return switch (plan) {
            case FREE -> freePerDay + Math.max(0, adCreditsToday);
            case PRO -> proPerDay;
        };
    }

    /** What pays for the session with this number today (1 is the first of the day). */
    public Reservation.Source sourceOf(Plan plan, int sessionNumberToday) {
        return switch (plan) {
            case FREE -> sessionNumberToday <= freePerDay ? Reservation.Source.FREE : Reservation.Source.REWARDED;
            case PRO -> Reservation.Source.PRO;
        };
    }

    /** The state to answer when the cap is reached. */
    public QuotaState refusal(Plan plan) {
        return switch (plan) {
            case FREE -> QuotaState.QUOTA_ZERO;
            case PRO -> QuotaState.PRO_CAP;
        };
    }
}
