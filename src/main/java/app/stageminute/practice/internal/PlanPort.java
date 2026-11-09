package app.stageminute.practice.internal;

import app.stageminute.platform.UserId;
import java.time.LocalDate;

/**
 * What {@code practice} needs to know from {@code money}: a person's plan and the ad credits they hold today.
 * {@code money} has no code until #22 and #24, so {@link FreeOnlyPlanPort} answers until then (AD-106); the
 * real adapter replaces it without any change to the quota rules.
 */
public interface PlanPort {

    Plan planOf(UserId user);

    int adCreditsToday(UserId user, LocalDate day);

    /**
     * The AI-cost throttle blocked someone who may have watched a rewarded ad (03 F-087, 04 section 1: "give the credit
     * back"): the credit must not be used up by a refusal. {@code practice} calls it when the throttle refuses a
     * person who holds a credit, and when a session is given back because the throttle stopped its analysis. Rewarded
     * ads and the {@code ad_credits} table belong to #24, so until then nothing is held and this does nothing.
     */
    default void creditBackAfterThrottle(UserId user, LocalDate day) {}
}
