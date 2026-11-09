package app.stageminute.analysis.vendor;

import app.stageminute.platform.RequestContext;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A circuit breaker and a bulkhead for one vendor (AD-151, #19.1). The breaker opens when too many of the latest calls
 * failed and then refuses calls for a while without calling the vendor (so nothing is spent); the bulkhead caps how many
 * calls to the vendor run at once, so a slow vendor cannot use every thread. A refusal is a {@link VendorUnavailable}
 * with a fixed code. The state is readable through {@link #state()}; metrics carry the vendor name only.
 *
 * <p>#19.2 (03 F-087 "alert when the breaker opens"): each time the breaker opens, the counter {@code
 * ai.breaker.open{vendor}} goes up and one line is logged with the vendor name only; sending that to the founder's
 * phone is #7 and #26. A {@link SpendRefused} from the spend meter passes through untouched: it is neither a failure nor
 * a success of the vendor, so it must not move the failure rate or close a half-open breaker.
 */
public final class VendorGuard {

    private static final Logger LOG = LoggerFactory.getLogger(VendorGuard.class);

    /** Where the breaker is. */
    public enum State {
        CLOSED(0),
        HALF_OPEN(1),
        OPEN(2);

        private final int gauge;

        State(int gauge) {
            this.gauge = gauge;
        }

        /** The number the {@code vendor.breaker.state} gauge shows: 0 closed, 1 half open, 2 open. */
        public int gauge() {
            return gauge;
        }
    }

    private final Vendor vendor;
    private final CircuitBreaker breaker;
    private final Bulkhead bulkhead;
    private final Counter refused;

    VendorGuard(Vendor vendor, VendorLimits limits, MeterRegistry meters) {
        this.vendor = vendor;
        String name = "vendor-" + vendor.tag();
        this.breaker = CircuitBreaker.of(
                name,
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(limits.breakerFailurePercent())
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(limits.breakerWindowCalls())
                        .minimumNumberOfCalls(limits.breakerMinCalls())
                        .waitDurationInOpenState(Duration.ofSeconds(limits.breakerOpenSeconds()))
                        .permittedNumberOfCallsInHalfOpenState(limits.breakerHalfOpenCalls())
                        .automaticTransitionFromOpenToHalfOpenEnabled(false)
                        .recordException(e -> e instanceof VendorUnavailable)
                        .ignoreExceptions(SpendRefused.class)
                        .build());
        this.bulkhead = Bulkhead.of(
                name,
                BulkheadConfig.custom()
                        .maxConcurrentCalls(limits.bulkheadMaxCalls())
                        .maxWaitDuration(Duration.ZERO)
                        .build());
        // vendor.call.refused: calls refused by the breaker or the bulkhead before the vendor was called
        this.refused = Counter.builder("vendor.call.refused")
                .tag("vendor", vendor.tag())
                .register(meters);
        Gauge.builder("vendor.breaker.state", this, guard -> guard.state().gauge())
                .description("0 closed, 1 half open, 2 open")
                .tag("vendor", vendor.tag())
                .register(meters);
        breaker.getEventPublisher().onStateTransition(event -> {
            CircuitBreaker.State to = event.getStateTransition().getToState();
            Counter.builder("vendor.breaker.transitions")
                    .tag("vendor", vendor.tag())
                    .tag("to", to.name().toLowerCase(Locale.ROOT))
                    .register(meters)
                    .increment();
            if (to == CircuitBreaker.State.OPEN) {
                // the alert of F-087 "circuit breaker open": a counter and one line, the vendor name only
                Counter.builder("ai.breaker.open")
                        .tag("vendor", vendor.tag())
                        .register(meters)
                        .increment();
                RequestContext.withoutPerson(() -> LOG.warn("ai.breaker.open vendor={}", vendor.tag()));
            }
        });
    }

    /** The vendor this guard protects. */
    public Vendor vendor() {
        return vendor;
    }

    /** Where the breaker is now. */
    public State state() {
        return switch (breaker.getState()) {
            case CLOSED, DISABLED, METRICS_ONLY -> State.CLOSED;
            case HALF_OPEN -> State.HALF_OPEN;
            case OPEN, FORCED_OPEN -> State.OPEN;
        };
    }

    /**
     * Runs the call unless the breaker is open or the bulkhead is full.
     *
     * @throws VendorUnavailable {@code breaker_open} or {@code bulkhead_full} without calling the vendor; or whatever
     *     the call threw, which the breaker counts as a failure
     */
    <T> T call(Supplier<T> action) {
        try {
            return Bulkhead.decorateSupplier(bulkhead, CircuitBreaker.decorateSupplier(breaker, action))
                    .get();
        } catch (CallNotPermittedException e) {
            refused.increment();
            throw new VendorUnavailable("breaker_open");
        } catch (BulkheadFullException e) {
            refused.increment();
            throw new VendorUnavailable("bulkhead_full");
        }
    }
}
