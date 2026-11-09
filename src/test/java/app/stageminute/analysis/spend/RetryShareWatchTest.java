package app.stageminute.analysis.spend;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.support.MutableClock;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Retries and time-outs, the cost side (06 F-122): the share of calls that were retries is a gauge, and a warning is
 * logged when it passes the setting (proposed 2 %) once enough calls were counted. The counters themselves are the ones
 * the vendor clients already keep ({@code vendor.call{vendor,outcome}}, {@code vendor.retry{vendor,outcome}}).
 */
class RetryShareWatchTest {

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-02T10:00:00Z"));
    private final RetryShareWatch watch = new RetryShareWatch(meters, clock);
    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RetryShareWatch.class);

    @BeforeEach
    void capture() {
        lines.start();
        logger.addAppender(lines);
    }

    @AfterEach
    void release() {
        logger.detachAppender(lines);
    }

    private void calls(String vendor, String outcome, int n) {
        meters.counter("vendor.call", "vendor", vendor, "outcome", outcome).increment(n);
    }

    private void retries(String vendor, int n) {
        meters.counter("vendor.retry", "vendor", vendor, "outcome", "network").increment(n);
    }

    private double shareGauge() {
        return meters.get("ai.retry.share").gauge().value();
    }

    @Test
    void theShareIsRetriesOverCallsInWholePercentAcrossBothVendors() {
        calls("deepgram", "ok", 60);
        calls("gemini", "ok", 40);
        retries("deepgram", 3);

        assertThat(shareGauge()).isEqualTo(3.0);
    }

    @Test
    void noCallsMeansNoShare() {
        assertThat(shareGauge()).isZero();
    }

    @Test
    void aShareOverTheSettingWarnsOncePerUtcDayWithThePercentOnly() {
        calls("deepgram", "ok", 100);
        retries("deepgram", 3);

        watch.check(2, 50);
        watch.check(2, 50);

        assertThat(lines.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("ai.retry.share_high percent=3");

        clock.advance(Duration.ofDays(1));
        watch.check(2, 50);
        assertThat(lines.list).hasSize(2);
    }

    @Test
    void aShareAtOrUnderTheSettingDoesNotWarn() {
        calls("gemini", "ok", 100);
        retries("gemini", 2); // exactly 2 %

        watch.check(2, 50);

        assertThat(lines.list).isEmpty();
    }

    @Test
    void tooFewCallsAreNotEnoughToJudge() {
        calls("gemini", "ok", 10);
        retries("gemini", 5); // 50 %, but only 10 calls

        watch.check(2, 50);

        assertThat(lines.list).isEmpty();
    }
}
