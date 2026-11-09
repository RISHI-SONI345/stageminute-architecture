package app.stageminute.trust.run;

import app.stageminute.analysis.spend.SpendPrices;
import app.stageminute.analysis.vendor.UsageSink;
import app.stageminute.analysis.vendor.VendorUsage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adds up what each real vendor call used, in millionths of a dollar, with the server's own price conversion
 * ({@link SpendPrices}). It receives units only, never speech. {@link #drain} gives the cost since the last drain, so
 * the runner can attach a cost to each run.
 */
public final class UsageMeter implements UsageSink {

    private final SpendPrices prices;
    private final AtomicLong micros = new AtomicLong();

    public UsageMeter(SpendPrices prices) {
        this.prices = prices;
    }

    @Override
    public void used(VendorUsage usage) {
        micros.addAndGet(prices.cost(usage));
    }

    /** The cost since the last call, and the counter back to zero. */
    public long drain() {
        return micros.getAndSet(0);
    }
}
