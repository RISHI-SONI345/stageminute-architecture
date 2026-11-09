package app.stageminute.platform;

/**
 * What the AI-cost throttle says about one person right now (03 F-087, D-58): carry on, free users are paused for the
 * rest of the UTC day, or a Pro person's daily ceiling is lowered to {@code proCeiling}. A plain value: the rule that
 * makes it is {@code analysis.spend.ThrottlePolicy}.
 *
 * @param kind which of the three
 * @param proCeiling the lowered Pro ceiling, only for {@link Kind#PRO_REDUCED}, else 0
 */
public record SpendDecision(Kind kind, int proCeiling) {

    /** The three answers. */
    public enum Kind {
        ALLOW,
        FREE_PAUSED,
        PRO_REDUCED
    }

    public static final SpendDecision ALLOW = new SpendDecision(Kind.ALLOW, 0);
    public static final SpendDecision FREE_PAUSED = new SpendDecision(Kind.FREE_PAUSED, 0);

    /** Pro keeps working but only up to {@code ceiling} a day. */
    public static SpendDecision proReduced(int ceiling) {
        return new SpendDecision(Kind.PRO_REDUCED, ceiling);
    }
}
