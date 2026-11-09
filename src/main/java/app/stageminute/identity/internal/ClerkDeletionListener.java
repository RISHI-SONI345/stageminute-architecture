package app.stageminute.identity.internal;

import app.stageminute.account.Deletion;
import app.stageminute.identity.vendor.ClerkUsers;
import app.stageminute.platform.DeleteOutcome;
import app.stageminute.platform.VendorDeletes;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Deletes the person's Clerk user after their account is deleted (05 F-082, 10-AUTH-CLERK). It runs after the commit
 * on its own and is safe to repeat: Clerk answering "no such user" is done. A failure leaves the event incomplete, so
 * the registry tries again (AD-87). Clerk then sends {@code user.deleted} back; that webhook finds no account and is
 * ignored.
 */
@Component
class ClerkDeletionListener {

    private static final String VENDOR = "clerk";

    private final ClerkUsers clerk;
    private final VendorDeletes deletes;

    ClerkDeletionListener(ClerkUsers clerk, VendorDeletes deletes) {
        this.clerk = clerk;
        this.deletes = deletes;
    }

    @ApplicationModuleListener
    void on(Deletion.Requested event) {
        DeleteOutcome outcome = clerk.delete(event.clerkUserId());
        deletes.record(VENDOR, outcome);
    }
}
