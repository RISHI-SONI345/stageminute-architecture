package app.stageminute.money.vendor;

import app.stageminute.platform.DeleteOutcome;
import app.stageminute.platform.VendorCallFailed;

/**
 * RevenueCat's "delete this customer" call (05 F-082, 03 F-094). A port, so the listener never knows the vendor and
 * a test runs with a fake. Safe to repeat: a customer RevenueCat does not have counts as done. Deleting here does not
 * cancel a store subscription; the person is told that on the confirm screen (EC-104).
 */
public interface RevenueCatSubscribers {

    /**
     * Deletes the customer and their purchase history at RevenueCat.
     *
     * @throws VendorCallFailed when RevenueCat did not take it; the caller lets it fly so the event is retried
     */
    DeleteOutcome delete(String appUserId);
}
