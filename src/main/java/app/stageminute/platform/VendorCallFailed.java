package app.stageminute.platform;

/**
 * An outside service did not take a delete (5xx, 429, a wrong key, or no answer). Thrown out of the event listener, so
 * the event stays incomplete and is tried again (AD-87). The message holds the vendor name and the status number only:
 * never the address, the key or the person's id.
 */
public final class VendorCallFailed extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String vendor;
    private final int status;

    /** @param status the HTTP status, or -1 when there was no answer */
    public VendorCallFailed(String vendor, int status) {
        super(vendor + " delete failed, status " + status, null, false, false);
        this.vendor = vendor;
        this.status = status;
    }

    public String vendor() {
        return vendor;
    }

    public int status() {
        return status;
    }
}
