package app.stageminute.practice;

/**
 * Who may make another attempt on a topic (03 F-047, D-15): 2 attempts, and a third only after Better or Not yet on
 * the last base attempt. Pure: no clock, no user, no tier, no database. The caller counts scored attempts only,
 * because failed and no-score takes are not attempts, and passes the focus outcome it got from the analysis module.
 * The quota and Pro caps (F-051, F-052) and the account-day end are checked elsewhere.
 */
public final class AttemptRule {

    private AttemptRule() {}

    /**
     * Whether another attempt may start, and why.
     *
     * @param scoredAttempts scored attempts done so far on this topic, at least 1
     * @param outcome the focus result of the attempt just done
     */
    public static NextAttempt next(int scoredAttempts, FocusOutcome outcome, AttemptSettings settings) {
        if (scoredAttempts < 1) {
            throw new IllegalArgumentException("scoredAttempts is at least 1, was " + scoredAttempts);
        }
        if (scoredAttempts >= settings.maxAttempts()) {
            return new NextAttempt(false, NextAttemptReason.ATTEMPT_CAP);
        }
        if (scoredAttempts < settings.baseAttempts()) {
            return new NextAttempt(true, NextAttemptReason.SECOND_ATTEMPT);
        }
        return switch (outcome) {
            case DONE -> new NextAttempt(false, NextAttemptReason.FOCUS_DONE);
            case BETTER, NOT_YET -> new NextAttempt(true, NextAttemptReason.LAST_TRY);
            case NONE -> new NextAttempt(false, NextAttemptReason.FOCUS_UNKNOWN);
        };
    }
}
