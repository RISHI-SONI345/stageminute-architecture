package app.stageminute.analysis.vendor;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The breaker and the bulkhead (AD-151, #19.1): an open breaker calls nothing and spends nothing, a full bulkhead
 * refuses at once, and the state is readable and counted with the vendor name only.
 */
class VendorGuardTest {

    private final MeterRegistry meters = new SimpleMeterRegistry();

    private static VendorUnavailable boom() {
        return new VendorUnavailable("stt_http_5xx");
    }

    @Test
    void theBreakerOpensAfterEnoughFailuresAndThenRefusesWithoutCallingTheVendor() {
        VendorGuard guard = new VendorGuard(Vendor.DEEPGRAM, TestLimits.launch().withBreakerMinCalls(2), meters);
        List<String> calls = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> guard.call(() -> {
                        calls.add("vendor");
                        throw boom();
                    }))
                    .hasMessage("stt_http_5xx");
        }
        assertThat(guard.state()).isEqualTo(VendorGuard.State.OPEN);

        assertThatThrownBy(() -> guard.call(() -> {
                    calls.add("vendor");
                    return "never";
                }))
                .isInstanceOf(VendorUnavailable.class)
                .hasMessage("breaker_open");
        assertThat(calls).hasSize(2); // the third call never reached the vendor
    }

    @Test
    void successesKeepItClosed() {
        VendorGuard guard = new VendorGuard(Vendor.GEMINI, TestLimits.launch().withBreakerMinCalls(2), meters);

        for (int i = 0; i < 5; i++) {
            assertThat(guard.call(() -> "ok")).isEqualTo("ok");
        }

        assertThat(guard.state()).isEqualTo(VendorGuard.State.CLOSED);
        assertThat(guard.vendor()).isEqualTo(Vendor.GEMINI);
    }

    @Test
    void theStateAndTheRefusalsAreMetricsThatCarryTheVendorNameOnly() {
        VendorGuard guard = new VendorGuard(Vendor.DEEPGRAM, TestLimits.launch().withBreakerMinCalls(2), meters);
        assertThat(meters.get("vendor.breaker.state")
                        .tag("vendor", "deepgram")
                        .gauge()
                        .value())
                .isZero();

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> guard.call(() -> {
                        throw boom();
                    }))
                    .isInstanceOf(VendorUnavailable.class);
        }
        assertThatThrownBy(() -> guard.call(() -> "x")).hasMessage("breaker_open");

        assertThat(meters.get("vendor.breaker.state")
                        .tag("vendor", "deepgram")
                        .gauge()
                        .value())
                .isEqualTo(2.0);
        assertThat(meters.get("vendor.breaker.transitions")
                        .tag("vendor", "deepgram")
                        .tag("to", "open")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(meters.get("vendor.call.refused")
                        .tag("vendor", "deepgram")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        meters.getMeters()
                .forEach(meter -> assertThat(meter.getId().getTags())
                        .allSatisfy(tag -> assertThat(tag.getKey()).isIn("vendor", "to", "outcome")));
    }

    @Test
    void aFullBulkheadRefusesAtOnceInsteadOfWaitingForAThread() throws Exception {
        VendorGuard guard = new VendorGuard(Vendor.GEMINI, TestLimits.launch().withBulkheadMaxCalls(1), meters);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> slow = pool.submit(() -> guard.call(() -> {
                inside.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "slow";
            }));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> guard.call(() -> "second"))
                    .isInstanceOf(VendorUnavailable.class)
                    .hasMessage("bulkhead_full");

            release.countDown();
            assertThat(slow.get(5, TimeUnit.SECONDS)).isEqualTo("slow");
            assertThat(guard.call(() -> "after")).isEqualTo("after");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anOpenBreakerThroughTheRealClientCallsNoVendorAndSpendsNothing() {
        WireMockServer wire = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wire.start();
        try {
            wire.stubFor(
                    post(urlPathEqualTo("/v1/listen")).willReturn(aResponse().withStatus(503)));
            TestLimits limits = TestLimits.launch().withBreakerMinCalls(2);
            List<String> events = new ArrayList<>();
            UsageSink sink = new UsageSink() {
                @Override
                public void starting(Vendor vendor, long audioMs) {
                    events.add("starting");
                }

                @Override
                public void used(VendorUsage usage) {
                    events.add("used");
                }

                @Override
                public void noUsageReported(Vendor vendor) {
                    events.add("noUsage");
                }
            };
            DeepgramClient client = new DeepgramClient(
                    new VendorEndpoint("http://localhost:" + wire.port(), "k", "nova-3"),
                    new VendorTransport(),
                    limits,
                    sink,
                    new VendorGuard(Vendor.DEEPGRAM, limits, meters),
                    meters);
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> client.transcribe(new byte[] {1}, 30_000, SttRequest.launch()))
                        .hasMessage("stt_http_5xx");
            }
            events.clear();
            wire.resetRequests();

            assertThatThrownBy(() -> client.transcribe(new byte[] {1}, 30_000, SttRequest.launch()))
                    .isInstanceOf(VendorUnavailable.class)
                    .hasMessage("breaker_open");

            assertThat(wire.findAll(postRequestedFor(urlPathEqualTo("/v1/listen"))))
                    .isEmpty();
            assertThat(events).isEmpty(); // not even a "starting": the spend meter is never asked
        } finally {
            wire.stop();
        }
    }

    // ---- #19.2: the spend meter's refusal is not the vendor's failure; the alert when a breaker opens ----------

    @Test
    void aRefusalByTheSpendMeterIsNeitherAFailureNorASuccessForTheBreaker() {
        VendorGuard guard = new VendorGuard(Vendor.GEMINI, TestLimits.launch().withBreakerMinCalls(4), meters);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> guard.call(() -> {
                        throw boom();
                    }))
                    .isInstanceOf(VendorUnavailable.class);
        }
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> guard.call(() -> {
                        throw new SpendRefused("spend_stop");
                    }))
                    .isInstanceOf(SpendRefused.class);
        }

        // Two real failures in a window that still needs four calls: if the refusals counted as successes the window
        // would hold four calls at 50 % failed and the breaker would be open by now.
        assertThat(guard.state()).isEqualTo(VendorGuard.State.CLOSED);
    }

    @Test
    void whenABreakerOpensItIsCountedAndLoggedOnceWithTheVendorNameOnly() {
        Logger logger = (Logger) LoggerFactory.getLogger(VendorGuard.class);
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        logger.addAppender(lines);
        try {
            VendorGuard guard =
                    new VendorGuard(Vendor.DEEPGRAM, TestLimits.launch().withBreakerMinCalls(2), meters);
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> guard.call(() -> {
                    throw boom();
                }));
            }
            assertThatThrownBy(() -> guard.call(() -> "refused while open"));

            assertThat(meters.get("ai.breaker.open")
                            .tag("vendor", "deepgram")
                            .counter()
                            .count())
                    .isEqualTo(1.0);
            assertThat(lines.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("ai.breaker.open vendor=deepgram");
        } finally {
            logger.detachAppender(lines);
        }
    }
}
