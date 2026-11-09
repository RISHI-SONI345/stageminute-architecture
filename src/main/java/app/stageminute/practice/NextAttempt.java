package app.stageminute.practice;

import java.util.Objects;

/**
 * The answer the report carries as `next_attempt` (03 F-047).
 *
 * @param allowed whether another attempt on this topic may start
 * @param reason why
 */
public record NextAttempt(boolean allowed, NextAttemptReason reason) {

    public NextAttempt {
        Objects.requireNonNull(reason, "reason");
    }
}
