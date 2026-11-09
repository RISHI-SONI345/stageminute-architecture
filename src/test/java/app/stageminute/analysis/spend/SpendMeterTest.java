package app.stageminute.analysis.spend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.stageminute.analysis.vendor.SpendRefused;
import app.stageminute.analysis.vendor.Vendor;
import app.stageminute.analysis.vendor.VendorUsage;
import app.stageminute.platform.SpendDecision;
import app.stageminute.platform.SpendGate.Tier;
import app.stageminute.support.Concurrently;
import app.stageminute.support.MutableClock;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The spend meter (#19.2; 03 F-087, F-122). It reserves the worst case of a call before the call, turns the reserve
 * into confirmed spend when the vendor reports usage, and keeps a call with no report counted for a short window.
 */
class SpendMeterTest {

    private static final long USD = 1_000_000L;
    private static final long GEMINI_WORST = 73_440; // 8 000 tokens in and 8 192 out at the published prices
    private static final SpendPrices PRICES = new SpendPrices(5_200, 1_500_000, 7_500_000, 8_000, 8_192);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-02T10:00:00Z"));
    private final InMemorySpendStore store = new InMemorySpendStore();
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicReference<SpendLimits> limits = new AtomicReference<>(limits(USD));
    private final SpendMeter meter = new SpendMeter(store, limits::get, clock, meters);

    private static SpendLimits limits(long budgetMicros) {
        return new SpendLimits(
                new ThrottlePolicy.Levels(budgetMicros, 70, 100, 150, 10),
                160,
                300,
                PRICES,
                ThrottleOverride.AUTO,
                2,
                50);
    }

    private double alerts(String level) {
        return meters.counter("ai.spend.level", "level", level).count();
    }

    // ---- 1: check before the call, record after ------------------------------------------------------------

    @Test
    void aCallReservesItsWorstCaseBeforeItIsMade() {
        meter.starting(Vendor.GEMINI, 0);
        assertThat(meter.reservedMicros()).isEqualTo(GEMINI_WORST);
        meter.noUsageReported(Vendor.GEMINI);
    }

    @Test
    void aDeepgramCallReservesTheWholeTakeAtTheMinutePrice() {
        meter.starting(Vendor.DEEPGRAM, 60_000);
        assertThat(meter.reservedMicros()).isEqualTo(5_200);
    }

    @Test
    void reportedUsageTurnsTheReserveIntoConfirmedSpendAndReleasesTheDifference() {
        meter.starting(Vendor.GEMINI, 0);
        meter.used(VendorUsage.tokens(1_100, 400));

        assertThat(meter.reservedMicros()).isZero();
        assertThat(meter.today()).isEqualTo(new SpendToday(4_650, 1, 4_650));
    }

    @Test
    void aRetryReservesAgainAndBothAnsweredCallsAreConfirmed() {
        meter.starting(Vendor.GEMINI, 0);
        meter.used(VendorUsage.tokens(1_100, 400)); // invalid output: answered, so paid
        meter.starting(Vendor.GEMINI, 0); // the retry
        assertThat(meter.reservedMicros()).isEqualTo(GEMINI_WORST);
        meter.used(VendorUsage.tokens(1_100, 400));

        assertThat(meter.reservedMicros()).isZero();
        assertThat(meter.today()).isEqualTo(new SpendToday(9_300, 2, 4_650));
    }

    @Test
    void aCallThePolicyRefusesReservesNothingAndWritesNothing() {
        limits.set(limits(50_000)); // the stop is 80 000, the worst case of a Gemini call 73 440
        meter.starting(Vendor.GEMINI, 0);
        meter.noUsageReported(Vendor.GEMINI);
        long before = meter.reservedMicros();

        assertThatThrownBy(() -> meter.starting(Vendor.GEMINI, 0)).isInstanceOf(SpendRefused.class);

        assertThat(meter.reservedMicros()).isEqualTo(before);
        assertThat(meter.today().calls()).isZero();
    }

    // ---- 2: concurrent retries cannot pass the budget ------------------------------------------------------

    @Test
    void manyThreadsMakingCallsAndRetriesCannotPassTheCallStop() {
        // The stop is 160 % of USD 1 = 1 600 000; one Gemini call holds 73 440, so 21 calls fit and the 22nd cannot.
        int fit = (int) (1_600_000 / GEMINI_WORST);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        Concurrently.run(64, () -> {
            for (int attempt = 0; attempt < 2; attempt++) { // the call, then a retry
                try {
                    meter.starting(Vendor.GEMINI, 0);
                    accepted.incrementAndGet();
                    meter.noUsageReported(Vendor.GEMINI); // a time-out: still counted
                } catch (SpendRefused e) {
                    refused.incrementAndGet();
                }
            }
            return null;
        });

        assertThat(accepted.get()).isEqualTo(fit);
        assertThat(refused.get()).isEqualTo(128 - fit);
        assertThat(meter.reservedMicros()).isEqualTo(fit * GEMINI_WORST).isLessThanOrEqualTo(1_600_000);
    }

    @Test
    void confirmedPlusReservedNeverPassesTheStopEvenWhileCallsAreSettling() {
        AtomicInteger over = new AtomicInteger();
        Concurrently.run(32, () -> {
            for (int i = 0; i < 20; i++) {
                try {
                    meter.starting(Vendor.GEMINI, 0);
                    meter.used(VendorUsage.tokens(8_000, 8_192)); // the worst case, exactly
                } catch (SpendRefused e) {
                    // refused: fine
                }
                if (meter.spentMicros() > 1_600_000) { // confirmed plus reserved, read together
                    over.incrementAndGet();
                }
            }
            return null;
        });

        assertThat(over.get()).isZero();
        assertThat(meter.today().confirmedMicros()).isLessThanOrEqualTo(1_600_000);
    }

    // ---- 3: exposure, leaks, restart -----------------------------------------------------------------------

    @Test
    void aCallWithNoUsageReportStaysCountedForTheWindowAndThenFallsAway() {
        meter.starting(Vendor.GEMINI, 0);
        meter.noUsageReported(Vendor.GEMINI); // a time-out

        clock.advance(Duration.ofSeconds(299));
        assertThat(meter.reservedMicros()).isEqualTo(GEMINI_WORST);
        clock.advance(Duration.ofSeconds(2));
        assertThat(meter.reservedMicros()).isZero();
        assertThat(meter.today().calls()).isZero(); // never turned into confirmed spend
    }

    @Test
    void aReserveWhoseCallEndedInAnExceptionNeverLeaks() {
        try {
            meter.starting(Vendor.GEMINI, 0);
            throw new IllegalStateException("the client broke before it could report");
        } catch (IllegalStateException expected) {
            // nobody called used() or noUsageReported(): the hold still ends by itself
        }
        assertThat(meter.reservedMicros()).isEqualTo(GEMINI_WORST);

        clock.advance(Duration.ofSeconds(301));
        assertThat(meter.reservedMicros()).isZero();
    }

    @Test
    void aNewCallOnTheSameThreadClosesTheOneThatWasNeverReported() {
        meter.starting(Vendor.GEMINI, 0); // never reported
        meter.starting(Vendor.GEMINI, 0); // the next call on this thread
        meter.used(VendorUsage.tokens(1_100, 400));

        assertThat(meter.reservedMicros()).isEqualTo(GEMINI_WORST); // the first one, now a timed exposure
        clock.advance(Duration.ofSeconds(301));
        assertThat(meter.reservedMicros()).isZero();
    }

    @Test
    void confirmedSpendSurvivesARestart() {
        meter.starting(Vendor.GEMINI, 0);
        meter.used(VendorUsage.tokens(1_100, 400));

        SpendMeter afterRestart = new SpendMeter(store, limits::get, clock, new SimpleMeterRegistry());

        assertThat(afterRestart.today()).isEqualTo(new SpendToday(4_650, 1, 4_650));
        assertThat(afterRestart.reservedMicros()).isZero(); // reserves are memory only, and one server runs at launch
    }

    @Test
    void theNextUtcDayStartsFromZeroWithoutAnyJob() {
        meter.starting(Vendor.GEMINI, 0);
        meter.used(VendorUsage.tokens(0, 200_000)); // USD 1.50: 150 %
        assertThat(meter.decide(Tier.FREE)).isEqualTo(SpendDecision.FREE_PAUSED);

        clock.advance(Duration.ofHours(14)); // 2026-11-03 00:00 UTC

        assertThat(meter.decide(Tier.FREE)).isEqualTo(SpendDecision.ALLOW);
        assertThat(meter.today()).isEqualTo(new SpendToday(0, 0, 0));
    }

    // ---- 4: the decision --------------------------------------------------------------------------------------

    @Test
    void freeUsersArePausedAtTheBudgetProAtOneAndAHalfTimes() {
        meter.starting(Vendor.GEMINI, 0);
        meter.used(VendorUsage.tokens(0, 140_000)); // USD 1.05: 105 %
        assertThat(meter.decide(Tier.FREE)).isEqualTo(SpendDecision.FREE_PAUSED);
        assertThat(meter.decide(Tier.PRO)).isEqualTo(SpendDecision.ALLOW);

        meter.starting(Vendor.GEMINI, 0);
        meter.used(VendorUsage.tokens(0, 66_667)); // USD 1.55: 155 %
        assertThat(meter.decide(Tier.PRO)).isEqualTo(SpendDecision.proReduced(10));
    }

    @Test
    void reservedSpendPausesFreeUsersBeforeItIsConfirmed() {
        limits.set(limits(100_000)); // the stop is 160 000
        meter.starting(Vendor.GEMINI, 0);
        meter.noUsageReported(Vendor.GEMINI);
        assertThat(meter.decide(Tier.FREE)).isEqualTo(SpendDecision.ALLOW); // 73 440 of 100 000

        meter.starting(Vendor.GEMINI, 0);
        meter.noUsageReported(Vendor.GEMINI);
        assertThat(meter.decide(Tier.FREE)).isEqualTo(SpendDecision.FREE_PAUSED); // 146 880
    }

    @Test
    void aMissingOrBrokenNumberPausesEveryoneAndRefusesCalls() {
        limits.set(limits(0)); // no budget
        assertThat(meter.decide(Tier.FREE)).isEqualTo(SpendDecision.FREE_PAUSED);
        assertThat(meter.decide(Tier.PRO)).isEqualTo(SpendDecision.proReduced(10));
        assertThatThrownBy(() -> meter.starting(Vendor.GEMINI, 0)).isInstanceOf(SpendRefused.class);

        limits.set(new SpendLimits(
                new ThrottlePolicy.Levels(USD, 70, 100, 150, 10),
                160,
                300,
                new SpendPrices(0, 1, 1, 1, 1),
                ThrottleOverride.AUTO,
                2,
                50)); // a price of nothing
        assertThat(meter.decide(Tier.FREE)).isEqualTo(SpendDecision.FREE_PAUSED);
        assertThatThrownBy(() -> meter.starting(Vendor.DEEPGRAM, 1_000)).isInstanceOf(SpendRefused.class);
    }

    @Test
    void aStoreThatCannotBeReadPausesFreeUsersAndRefusesCallsInsteadOfOpeningTheLimit() {
        store.broken.set(true);

        assertThat(meter.decide(Tier.FREE)).isEqualTo(SpendDecision.FREE_PAUSED);
        assertThat(meter.decide(Tier.PRO)).isEqualTo(SpendDecision.proReduced(10));
        assertThatThrownBy(() -> meter.starting(Vendor.GEMINI, 0)).isInstanceOf(SpendRefused.class);
    }

    @Test
    void theManualOverrideForcesThePauseOrIgnoresTheLevelsButNotTheCallStop() {
        limits.set(new SpendLimits(limits(USD).levels(), 160, 300, PRICES, ThrottleOverride.FORCE_OPEN, 2, 50));
        assertThat(meter.decide(Tier.FREE)).isEqualTo(SpendDecision.FREE_PAUSED); // nothing spent yet
        assertThat(meter.decide(Tier.PRO)).isEqualTo(SpendDecision.proReduced(10));

        limits.set(new SpendLimits(limits(USD).levels(), 160, 300, PRICES, ThrottleOverride.FORCE_CLOSED, 2, 50));
        meter.starting(Vendor.GEMINI, 0);
        meter.used(VendorUsage.tokens(0, 200_000)); // 150 %
        assertThat(meter.decide(Tier.FREE)).isEqualTo(SpendDecision.ALLOW);
        assertThat(meter.decide(Tier.PRO)).isEqualTo(SpendDecision.ALLOW);

        meter.starting(Vendor.GEMINI, 0);
        meter.used(VendorUsage.tokens(0, 79_000)); // 1 500 000 + 592 500 is past the stop of 1 600 000
        assertThatThrownBy(() -> meter.starting(Vendor.GEMINI, 0)).isInstanceOf(SpendRefused.class);
    }

    // ---- 5: alerts once a day --------------------------------------------------------------------------------

    @Test
    void eachAlertLevelFiresOncePerUtcDay() {
        meter.starting(Vendor.GEMINI, 0);
        meter.used(VendorUsage.tokens(0, 100_000)); // USD 0.75: 75 %
        meter.starting(Vendor.GEMINI, 0);
        meter.used(VendorUsage.tokens(0, 1_000)); // still only the first alert
        assertThat(alerts("alert")).isEqualTo(1);
        assertThat(alerts("free_throttle")).isZero();

        meter.starting(Vendor.GEMINI, 0);
        meter.used(VendorUsage.tokens(0, 40_000)); // 105 %
        meter.starting(Vendor.GEMINI, 0);
        meter.used(VendorUsage.tokens(0, 66_667)); // 155 %: one more call would pass the stop of 160 %
        assertThat(alerts("alert")).isEqualTo(1);
        assertThat(alerts("free_throttle")).isEqualTo(1);
        assertThat(alerts("pro_throttle")).isEqualTo(1);

        clock.advance(Duration.ofDays(1));
        meter.starting(Vendor.GEMINI, 0);
        meter.used(VendorUsage.tokens(0, 100_000)); // a new day, the 70 % level again
        assertThat(alerts("alert")).isEqualTo(2);
    }

    // ---- 6: retries and time-outs (F-122) -----------------------------------------------------------------------

    @Test
    void aFinishedCallChecksTheRetryShareAndWarnsWhenItIsOverTheSetting() {
        Logger logger = (Logger) LoggerFactory.getLogger(RetryShareWatch.class);
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        logger.addAppender(lines);
        try {
            meters.counter("vendor.call", "vendor", "gemini", "outcome", "ok").increment(100);
            meters.counter("vendor.retry", "vendor", "gemini", "outcome", "invalid_output")
                    .increment(5);

            meter.starting(Vendor.GEMINI, 0);
            meter.noUsageReported(Vendor.GEMINI);

            assertThat(lines.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("ai.retry.share_high percent=5");
        } finally {
            logger.detachAppender(lines);
        }
    }
}
