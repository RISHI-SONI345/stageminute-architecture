package app.stageminute.practice;

/** Why the next attempt is or is not offered (03 F-047 `next_attempt.reason`). */
public enum NextAttemptReason {
    /** After attempt 1 a second attempt is always offered. */
    SECOND_ATTEMPT,
    /** After a Better or Not yet attempt 2 the third, "Last try", is offered. */
    LAST_TRY,
    /** Attempt 2 was Done: the primary button is "Next topic". */
    FOCUS_DONE,
    /** The cap is reached: "That was your last try on this topic." */
    ATTEMPT_CAP,
    /** Attempt 2 arrived with no focus result; nothing more is offered (fails closed, no extra analysis). */
    FOCUS_UNKNOWN
}
