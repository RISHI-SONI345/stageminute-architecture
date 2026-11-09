package app.stageminute.analysis.vendor;

/**
 * A vendor could not give an answer (03 F-029, F-122): a network error, a time-out, invalid output, or no vendor
 * configured at all (fail closed). The caller answers {@code 502 analysis_unavailable} and gives the session back. The
 * message is a fixed code, never vendor text or speech.
 */
public final class VendorUnavailable extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public VendorUnavailable(String code) {
        super(code, null, false, false);
    }
}
