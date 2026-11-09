package app.stageminute.analysis.spend;

/**
 * Every number the meter obeys, read together (from settings in the server, from the test in a test).
 *
 * @param levels the budget and the share levels
 * @param callStopPercent a vendor call is refused when confirmed plus reserved plus its worst case would pass this
 *     share of the budget
 * @param exposureSeconds how long a call with no usage report stays counted
 * @param prices the vendors' prices and worst-case sizes
 * @param override the manual override
 * @param retryAlertPercent retries over calls above this percent is a warning (F-122, proposed 2)
 * @param retryAlertMinCalls fewest calls counted before the retry share is judged
 */
public record SpendLimits(
        ThrottlePolicy.Levels levels,
        int callStopPercent,
        int exposureSeconds,
        SpendPrices prices,
        ThrottleOverride override,
        int retryAlertPercent,
        int retryAlertMinCalls) {

    /** False when the budget or a price is zero or less: the meter then pauses everyone (fail closed). */
    public boolean usable() {
        return levels.budgetMicros() > 0 && prices.valid() && callStopPercent > 0 && exposureSeconds > 0;
    }

    /** The most spend a day may reach before a call is refused. */
    public long callStopMicros() {
        return Math.multiplyExact(levels.budgetMicros(), (long) callStopPercent) / 100L;
    }
}
