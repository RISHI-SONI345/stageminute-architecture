package app.stageminute.platform;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records what each vendor delete came to: one counter {@code deletion.vendor{vendor,outcome}} and one log line with
 * the event name, the vendor and the outcome. Never an id: the log and the metric say how many, not who (F-081).
 * A vendor with no key is a warning, so a missing key is seen in the logs before it is a promise broken.
 */
@Component
public class VendorDeletes {

    private static final Logger LOG = LoggerFactory.getLogger(VendorDeletes.class);

    private final MeterRegistry meters;

    VendorDeletes(MeterRegistry meters) {
        this.meters = meters;
    }

    public void record(String vendor, DeleteOutcome outcome) {
        String name = outcome.name().toLowerCase(Locale.ROOT);
        meters.counter("deletion.vendor", "vendor", vendor, "outcome", name).increment();
        if (outcome == DeleteOutcome.NOT_CONFIGURED) {
            LOG.warn("deletion.vendor_not_configured vendor={}", vendor);
        } else {
            LOG.info("deletion.vendor vendor={} outcome={}", vendor, name);
        }
    }
}
