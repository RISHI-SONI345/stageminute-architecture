package app.stageminute.analysis.spend;

import app.stageminute.platform.RequestContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retries and time-outs, the cost side (06 F-122, 03 F-091): every retry costs money, so the share of calls that were
 * retries is a gauge ({@code ai.retry.share}, whole percent) and a warning is logged when it passes the setting
 * (proposed 2 %) once enough calls were counted. It reads the counters the vendor clients already keep
 * ({@code vendor.call}, {@code vendor.retry}; time-outs are {@code vendor.call{outcome=timeout}}, already counted per
 * vendor there); it counts nothing itself. The counters run since the server started, so a restart starts the share
 * again. The warning is logged once per UTC day, and once it has been logged the day's later calls cost nothing here.
 * Delivery of the alert is #7 and #26.
 */
final class RetryShareWatch {

    private static final Logger LOG = LoggerFactory.getLogger(RetryShareWatch.class);
    private static final double PERCENT = 100.0;

    private final MeterRegistry meters;
    private final Clock clock;
    private LocalDate warnedOn;

    RetryShareWatch(MeterRegistry meters, Clock clock) {
        this.meters = meters;
        this.clock = clock;
        Gauge.builder("ai.retry.share", this, watch -> share(watch.total("vendor.call"), watch.total("vendor.retry")))
                .description("retries over vendor calls, in percent")
                .register(meters);
    }

    /** Warns once a day when retries are more than {@code alertPercent} of at least {@code minCalls} calls. */
    synchronized void check(int alertPercent, int minCalls) {
        LocalDate today = SpendMeter.utcDay(clock.instant());
        if (today.equals(warnedOn)) {
            return; // already warned today: no need to scan the meters again
        }
        double calls = total("vendor.call");
        double share = share(calls, total("vendor.retry"));
        if (calls >= minCalls && share > alertPercent) {
            warnedOn = today;
            long percent = (long) share;
            RequestContext.withoutPerson(() -> LOG.warn("ai.retry.share_high percent={}", percent));
        }
    }

    private static double share(double calls, double retries) {
        return calls == 0 ? 0 : retries * PERCENT / calls;
    }

    private double total(String name) {
        return meters.find(name).counters().stream().mapToDouble(Counter::count).sum();
    }
}
