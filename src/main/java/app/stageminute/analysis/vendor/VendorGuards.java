package app.stageminute.analysis.vendor;

import java.util.List;

/**
 * The breaker and bulkhead of each vendor, in one place, so the monitoring and the spend meter (#19.2) can read the
 * state of both (AD-151). Built once at start; the guards exist even when a vendor is a fake or not configured.
 */
public final class VendorGuards {

    private final VendorGuard deepgram;
    private final VendorGuard gemini;

    VendorGuards(VendorGuard deepgram, VendorGuard gemini) {
        this.deepgram = deepgram;
        this.gemini = gemini;
    }

    /** The guard of one vendor. */
    public VendorGuard of(Vendor vendor) {
        return vendor == Vendor.DEEPGRAM ? deepgram : gemini;
    }

    /** Both guards. */
    public List<VendorGuard> all() {
        return List.of(deepgram, gemini);
    }
}
