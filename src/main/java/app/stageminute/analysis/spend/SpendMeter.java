package app.stageminute.analysis.spend;

import app.stageminute.analysis.vendor.SpendRefused;
import app.stageminute.analysis.vendor.UsageSink;
import app.stageminute.analysis.vendor.Vendor;
import app.stageminute.analysis.vendor.VendorUsage;
import app.stageminute.platform.SpendDecision;
import app.stageminute.platform.SpendGate;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The daily AI spend counter and the throttle (03 F-087, F-122; #19.2, AD-152). It sits on the usage seam of the real
 * vendor clients ({@link UsageSink}) and answers the question {@code practice} asks before it reserves a session
 * ({@link SpendGate}).
 *
 * <p><b>Two numbers.</b> <i>Confirmed</i> spend is what the vendors reported using: one row per UTC date in the
 * database ({@link SpendStore}), so a restart loses nothing (F-088). <i>Reserved</i> spend is the worst case of the
 * calls in flight, held in memory (one server runs at launch). Before a call and before a retry, {@link #starting}
 * checks confirmed plus reserved plus this call's worst case against the call stop and holds the worst case, in one
 * step under one lock, so many threads cannot pass the budget together. When the vendor reports usage, {@link #used}
 * adds the real cost to the day and drops the hold; the difference is released. A call that ends with no report
 * ({@link #noUsageReported}: a time-out, a dropped connection) keeps its hold for the exposure window and then it falls
 * away. Every hold has an end time, so nothing leaks, whatever happens to the caller.
 *
 * <p>Everything fails closed: a zero budget or price, or a store that cannot be read, pauses free users and refuses
 * calls. The plan is read in {@link #decide} only to choose the ceiling; the meter never sees a score. It logs and
 * counts levels and whole percents only: no amount per person, no id, no speech.
 */
public final class SpendMeter implements UsageSink, SpendGate {

    private static final Logger LOG = LoggerFactory.getLogger(SpendMeter.class);

    /** What is held back for one call in flight, and until when. */
    private static final class Hold {
        private final long micros;
        private Instant until;

        Hold(long micros, Instant until) {
            this.micros = micros;
            this.until = until;
        }
    }

    private final SpendStore store;
    private final Supplier<SpendLimits> limits;
    private final Clock clock;
    private final MeterRegistry meters;
    private final SpendAlerts alerts;
    private final RetryShareWatch retryShare;

    private final Object lock = new Object();
    private final Map<Long, Hold> holds = new HashMap<>(); // guarded by lock
    private long lastId; // guarded by lock
    private final ThreadLocal<Long> openHold = new ThreadLocal<>();

    public SpendMeter(SpendStore store, Supplier<SpendLimits> limits, Clock clock, MeterRegistry meters) {
        this.store = store;
        this.limits = limits;
        this.clock = clock;
        this.meters = meters;
        this.alerts = new SpendAlerts(meters);
        this.retryShare = new RetryShareWatch(meters, clock);
        Gauge.builder("ai.spend.reserved.micros", this, SpendMeter::reservedMicros)
                .description("worst-case cost held back for calls in flight or with no usage report yet")
                .register(meters);
    }

    // ---- the usage seam: check before the call, record after ------------------------------------------------

    @Override
    public void starting(Vendor vendor, long audioMs) {
        SpendLimits current = limits.get(); // read once: every setting is a query
        endOpenHold(
                current.exposureSeconds()); // a call on this thread that never reported counts as exposure from here on
        if (!current.usable()) {
            throw refused("spend_closed");
        }
        long worst = current.prices().worstCase(vendor, audioMs);
        synchronized (lock) {
            Instant now = clock.instant();
            purge(now);
            long confirmed = confirmedOrRefuse(now);
            if (confirmed + reservedLocked() + worst > current.callStopMicros()) {
                throw refused("spend_stop");
            }
            long id = ++lastId;
            holds.put(id, new Hold(worst, now.plusSeconds(current.exposureSeconds())));
            openHold.set(id);
        }
    }

    @Override
    public void used(VendorUsage usage) {
        SpendLimits current = limits.get();
        long cost = current.prices().cost(usage);
        Long id = openHold.get();
        openHold.remove();
        LocalDate day;
        long confirmedAfter;
        synchronized (lock) {
            Instant now = clock.instant();
            day = utcDay(now);
            try {
                confirmedAfter =
                        store.add(day, cost).micros(); // the day's total after this call, from the same statement
            } catch (RuntimeException e) {
                // The money was spent but could not be written: keep it counted for the window rather than lose it.
                meters.counter("ai.spend.write_failed").increment();
                LOG.error("ai.spend.write_failed vendor={}", usage.vendor().tag());
                holds.put(++lastId, new Hold(cost, now.plusSeconds(current.exposureSeconds())));
                if (id != null) {
                    holds.remove(id);
                }
                return;
            }
            if (id != null) {
                holds.remove(id);
            }
        }
        meters.counter("ai.spend.micros", "vendor", usage.vendor().tag()).increment((double) cost);
        alerts.check(confirmedAfter, current.levels(), day);
        retryShare.check(current.retryAlertPercent(), current.retryAlertMinCalls());
    }

    @Override
    public void noUsageReported(Vendor vendor) {
        SpendLimits current = limits.get();
        endOpenHold(current.exposureSeconds());
        retryShare.check(current.retryAlertPercent(), current.retryAlertMinCalls());
    }

    // ---- the question practice asks ---------------------------------------------------------------------------

    @Override
    public SpendDecision decide(Tier tier) {
        SpendLimits current = limits.get();
        if (!current.usable() || current.override() == ThrottleOverride.FORCE_OPEN) {
            return paused(tier, current);
        }
        if (current.override() == ThrottleOverride.FORCE_CLOSED) {
            return SpendDecision.ALLOW;
        }
        try {
            // The question is advisory (a call is checked again under the lock in starting), so the database read is
            // made before the lock: a slow database must not hold up every vendor call behind GET /me.
            Instant now = clock.instant();
            long confirmed = store.read(utcDay(now)).micros();
            synchronized (lock) {
                purge(now);
                return ThrottlePolicy.decide(confirmed, reservedLocked(), current.levels(), tier);
            }
        } catch (RuntimeException e) {
            LOG.warn("ai.spend.unreadable decision=paused"); // fail closed: an unreadable counter never opens the gate
            return paused(tier, current);
        }
    }

    // ---- reads ------------------------------------------------------------------------------------------------

    /** Worst-case cost held back right now for calls in flight and calls with no usage report yet. */
    public long reservedMicros() {
        synchronized (lock) {
            purge(clock.instant());
            return reservedLocked();
        }
    }

    /** Confirmed plus reserved spend today, read together. May throw when the store cannot be read. */
    public long spentMicros() {
        synchronized (lock) {
            Instant now = clock.instant();
            purge(now);
            return store.read(utcDay(now)).micros() + reservedLocked();
        }
    }

    /** Today's confirmed micros, calls and mean per call: for the trust harness and the admin console. */
    public SpendToday today() {
        return SpendToday.of(store.read(utcDay(clock.instant())));
    }

    // ---- inside -----------------------------------------------------------------------------------------------

    /** The call on this thread ended with no report: keep its hold for the window, then it falls away. */
    private void endOpenHold(int seconds) {
        Long id = openHold.get();
        if (id == null) {
            return;
        }
        openHold.remove();
        synchronized (lock) {
            Hold hold = holds.get(id);
            if (hold != null) {
                hold.until = clock.instant().plusSeconds(seconds);
            }
        }
    }

    private long confirmedOrRefuse(Instant now) {
        try {
            return store.read(utcDay(now)).micros();
        } catch (RuntimeException e) {
            throw refused("spend_unreadable");
        }
    }

    private SpendRefused refused(String code) {
        meters.counter("ai.spend.refused", "reason", code).increment();
        return new SpendRefused(code);
    }

    private long reservedLocked() {
        long total = 0;
        for (Hold hold : holds.values()) {
            total += hold.micros;
        }
        return total;
    }

    private void purge(Instant now) {
        holds.values().removeIf(hold -> !hold.until.isAfter(now));
    }

    /** The UTC date a spend row belongs to: the one rule for the day, shared with the retry watch. */
    static LocalDate utcDay(Instant now) {
        return LocalDate.ofInstant(now, ZoneOffset.UTC);
    }

    private static SpendDecision paused(Tier tier, SpendLimits current) {
        return tier == Tier.FREE
                ? SpendDecision.FREE_PAUSED
                : SpendDecision.proReduced(current.levels().proCeiling());
    }
}
