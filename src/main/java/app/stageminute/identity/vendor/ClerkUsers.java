package app.stageminute.identity.vendor;

import app.stageminute.platform.DeleteOutcome;
import app.stageminute.platform.VendorCallFailed;

/**
 * Clerk's "delete this user" call (Backend API, 05 F-082, 10-AUTH-CLERK). A port, so the listener never knows the
 * vendor and a test runs with a fake. Safe to repeat: a user Clerk does not have counts as done.
 */
public interface ClerkUsers {

    /**
     * Deletes the Clerk user (this also ends their sessions).
     *
     * @throws VendorCallFailed when Clerk did not take it; the caller lets it fly so the event is retried
     */
    DeleteOutcome delete(String clerkUserId);
}
