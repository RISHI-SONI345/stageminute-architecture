package app.stageminute.analysis.vendor;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real speech-to-text adapter (#19.1; 03 F-027, F-122; 05 F-080). One POST of the take's bytes, straight from
 * memory, to Deepgram's prerecorded endpoint; the address and options come from {@link DeepgramRequest} alone, so
 * {@code mip_opt_out=true} is on every call, the retry included.
 *
 * <p>Retry (F-122): once, on a network error only, and only if the time budget has {@code retryMinRemainingMs} left.
 * Never after an HTTP answer, never after a time-out. The circuit breaker and the bulkhead wrap the whole call. This
 * class never logs or returns vendor text; a failure is a {@link VendorUnavailable} with a fixed code.
 */
final class DeepgramClient implements SpeechToText {

    private static final Logger LOG = LoggerFactory.getLogger(DeepgramClient.class);
    private static final int MAX_ATTEMPTS = 2;
    private static final int OK_FROM = 200;
    private static final int OK_BELOW = 300;
    private static final int SERVER_ERROR_FROM = 500;

    private final VendorEndpoint endpoint;
    private final VendorTransport transport;
    private final VendorLimits limits;
    private final UsageSink usage;
    private final VendorGuard guard;
    private final MeterRegistry meters;

    DeepgramClient(
            VendorEndpoint endpoint,
            VendorTransport transport,
            VendorLimits limits,
            UsageSink usage,
            VendorGuard guard,
            MeterRegistry meters) {
        this.endpoint = endpoint;
        this.transport = transport;
        this.limits = limits;
        this.usage = usage;
        this.guard = guard;
        this.meters = meters;
    }

    @Override
    public SpeechResult transcribe(byte[] audio, int durationMs, SttRequest request) {
        if (!request.mipOptOut() || !request.fillerWords()) {
            throw new VendorUnavailable("stt_request_invalid"); // the requirements fix both options; nothing is sent
        }
        return guard.call(() -> transcribeWithRetry(audio, durationMs));
    }

    private SpeechResult transcribeWithRetry(byte[] audio, int durationMs) {
        for (int attempt = 1; ; attempt++) {
            usage.starting(Vendor.DEEPGRAM, durationMs);
            VendorTransport.Reply reply;
            try {
                reply = transport.post(
                        DeepgramRequest.uri(endpoint.baseUrl(), endpoint.model()),
                        Map.of("Authorization", "Token " + endpoint.apiKey()),
                        DeepgramRequest.CONTENT_TYPE,
                        audio,
                        VendorBudget.remainingMs(limits.budgetMs()));
            } catch (VendorTransport.Failure failure) {
                usage.noUsageReported(Vendor.DEEPGRAM);
                if (failure.kind() == VendorTransport.Kind.NETWORK
                        && attempt < MAX_ATTEMPTS
                        && VendorBudget.remainingMs(limits.budgetMs()) >= limits.retryMinRemainingMs()) {
                    count("vendor.retry", "network");
                    continue;
                }
                throw failed(failure.kind());
            }
            return answered(reply, durationMs);
        }
    }

    private SpeechResult answered(VendorTransport.Reply reply, int durationMs) {
        if (reply.status() < OK_FROM || reply.status() >= OK_BELOW) {
            usage.noUsageReported(Vendor.DEEPGRAM);
            String code = reply.status() >= SERVER_ERROR_FROM ? "stt_http_5xx" : "stt_http_4xx";
            count("vendor.call", code);
            throw new VendorUnavailable(code);
        }
        DeepgramAnswer.Parsed parsed;
        try {
            parsed = DeepgramAnswer.parse(reply.body(), durationMs);
        } catch (VendorUnavailable e) {
            usage.used(VendorUsage.audio(durationMs)); // an answer came back, so the audio was processed
            count("vendor.call", "bad_answer");
            throw e;
        }
        usage.used(VendorUsage.audio(parsed.billedMs().orElse(durationMs)));
        count("vendor.call", "ok");
        checkModel(parsed.answeredModel());
        return parsed.speech();
    }

    private VendorUnavailable failed(VendorTransport.Kind kind) {
        String outcome = switch (kind) {
            case TIMEOUT -> "timeout";
            case NETWORK -> "network";
            case INTERRUPTED -> "interrupted";
        };
        count("vendor.call", outcome);
        return new VendorUnavailable("stt_" + outcome);
    }

    /** The pinned name must appear in the name Deepgram answers with; a change is counted and logged (ids only). */
    private void checkModel(@Nullable String answered) {
        if (answered != null && !answered.contains(endpoint.model())) {
            meters.counter("vendor.model.mismatch", "vendor", Vendor.DEEPGRAM.tag())
                    .increment();
            LOG.warn(
                    "vendor.model_mismatch vendor={} pinned={} answered={}",
                    Vendor.DEEPGRAM.tag(),
                    endpoint.model(),
                    answered);
        }
    }

    private void count(String name, String outcome) {
        meters.counter(name, "vendor", Vendor.DEEPGRAM.tag(), "outcome", outcome)
                .increment();
    }
}
