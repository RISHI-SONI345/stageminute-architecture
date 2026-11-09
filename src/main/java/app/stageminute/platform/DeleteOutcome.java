package app.stageminute.platform;

/**
 * What one vendor delete came to (05 F-082, AD-149). A failure is not an outcome: it is a {@link VendorCallFailed}
 * thrown to the event registry, which tries again.
 */
public enum DeleteOutcome {
    /** The vendor accepted the delete. */
    DELETED,
    /** The vendor had no such person (404). The aim is reached, so this counts as done. */
    ALREADY_GONE,
    /** No key is set for this vendor, so nothing was sent and nothing will be retried (AD-149 rule). */
    NOT_CONFIGURED
}
