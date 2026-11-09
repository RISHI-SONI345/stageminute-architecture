package app.stageminute.analysis.vendor;

/**
 * The AI-cost meter refused a vendor call because the day's spend (confirmed plus reserved plus this call's worst
 * case) would pass the call stop (03 F-087, #19.2). It is thrown from {@link UsageSink#starting}, before anything is
 * sent, so the call costs nothing. It is not a {@link VendorUnavailable}: the vendor did not fail, so the breaker must
 * not count it, and the caller answers {@code 503 throttled}, not {@code 502 analysis_unavailable}. The message is a
 * fixed code, never an amount.
 */
public final class SpendRefused extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SpendRefused(String code) {
        super(code, null, false, false);
    }
}
