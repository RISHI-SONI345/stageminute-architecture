package app.stageminute.platform;

/**
 * The analytics vendor's "forget this person" call (PostHog; 05 F-082, 03 F-094). A port, so the listener in
 * {@code account} never knows the vendor, and a test runs with a fake. Safe to repeat: a person who is not there counts
 * as done.
 */
public interface PostHogPersons {

    /**
     * Deletes the person and their events.
     *
     * @param distinctId the opaque user id the app identified itself with (never an email)
     * @throws VendorCallFailed when the vendor did not take it; the caller lets it fly so the event is retried
     */
    DeleteOutcome delete(String distinctId);
}
