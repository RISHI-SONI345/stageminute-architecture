package app.stageminute.money.internal;

import app.stageminute.account.Deletion;
import app.stageminute.money.vendor.RevenueCatSubscribers;
import app.stageminute.platform.DeleteOutcome;
import app.stageminute.platform.VendorDeletes;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Deletes the person at RevenueCat after their account is deleted (05 F-082). Runs after the commit on its own and is
 * safe to repeat; a failure leaves the event incomplete so the registry tries again (AD-87).
 */
@Component
class RevenueCatDeletionListener {

    private static final String VENDOR = "revenuecat";

    private final RevenueCatSubscribers subscribers;
    private final VendorDeletes deletes;

    RevenueCatDeletionListener(RevenueCatSubscribers subscribers, VendorDeletes deletes) {
        this.subscribers = subscribers;
        this.deletes = deletes;
    }

    @ApplicationModuleListener
    void on(Deletion.Requested event) {
        DeleteOutcome outcome = subscribers.delete(event.revenueCatId());
        deletes.record(VENDOR, outcome);
    }
}
