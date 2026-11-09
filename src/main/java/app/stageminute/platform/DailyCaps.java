package app.stageminute.platform;

import java.time.LocalDate;

/**
 * A daily cap that a module needs but does not own (AD-147). Every daily counter lives in one row per person and
 * account day ({@code daily_counters}, owned by {@code practice}, AD-11); a module that is not allowed to use {@code
 * practice} counts through this port, and {@code practice} implements it with its guarded statement. The count is
 * one statement that only adds when it is under the cap, so two calls at the same moment cannot both pass.
 */
public interface DailyCaps {

    /** The counters other modules may add to. */
    enum Counter {
        /** {@code daily_counters.disputes}: score disputes (D-18). */
        DISPUTES
    }

    /**
     * Adds one to the counter of {@code day} when it is below {@code cap}. Joins the caller's transaction, so a
     * refusal later in the same transaction gives the count back.
     *
     * @param day the person's account day (from {@code account.AccountCalendar}), never the phone's date
     * @return false when the cap is reached (nothing was added)
     */
    boolean tryCount(UserId user, LocalDate day, Counter counter, int cap);
}
