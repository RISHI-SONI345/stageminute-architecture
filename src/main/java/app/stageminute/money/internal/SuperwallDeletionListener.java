package app.stageminute.money.internal;

import app.stageminute.account.Deletion;
import app.stageminute.platform.DeleteOutcome;
import app.stageminute.platform.VendorDeletes;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Superwall has no public delete call: its privacy page says "For customer data deletion requests, please contact us
 * at compliance@superwall.com" (checked 2026-11-01). So there is nothing to send and nothing to retry; the listener
 * records that the person is owed a manual deletion, as a warning in the log and a counter, and the event is done.
 * The founder decides how that manual step runs (conflict C-79) and whether the app should identify people to
 * Superwall at all.
 */
@Component
class SuperwallDeletionListener {

    private static final String VENDOR = "superwall";

    private final VendorDeletes deletes;

    SuperwallDeletionListener(VendorDeletes deletes) {
        this.deletes = deletes;
    }

    @ApplicationModuleListener
    void on(Deletion.Requested event) {
        deletes.record(VENDOR, DeleteOutcome.NOT_CONFIGURED);
    }
}
