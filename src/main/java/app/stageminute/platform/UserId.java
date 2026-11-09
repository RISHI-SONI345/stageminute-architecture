package app.stageminute.platform;

import java.util.UUID;

/**
 * Our id for a user: opaque, safe to put in logs and Sentry (AD-23). Wrapping the UUID stops a session id or a
 * report id from being passed where a user id is wanted.
 */
public record UserId(UUID value) {

    public UserId {
        java.util.Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
