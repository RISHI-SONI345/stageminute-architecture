package app.stageminute.analysis.vendor;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real judge (#19.1; 03 F-029, F-122, D-84): one labels-only call to Gemini per take. The prompt and the schema are
 * the files in {@code analysis/}, and their versions are made from their text, so every report names the exact prompt
 * (AD-125). Retry (F-122): once, and only for invalid output, and only if the time budget has {@code
 * retryMinRemainingMs} left. A time-out, a network error and any HTTP answer (5xx, 429, 4xx) are never retried, because
 * every retry costs money. The breaker and the bulkhead wrap the whole call. This class never logs or returns vendor
 * text or the take's words; a failure is a {@link VendorUnavailable} with a fixed code.
 */
final class GeminiJudge implements Judge {

    private static final Logger LOG = LoggerFactory.getLogger(GeminiJudge.class);
    private static final int MAX_ATTEMPTS = 2;
    private static final int OK_FROM = 200;
    private static final int OK_BELOW = 300;
    private static final int SERVER_ERROR_FROM = 500;
    private static final int TOO_MANY_REQUESTS = 429;

    private final VendorEndpoint endpoint;
    private final PromptFiles.Loaded files;
    private final Schema schema;
    private final VendorTransport transport;
    private final VendorLimits limits;
    private final UsageSink usage;
    private final VendorGuard guard;
    private final MeterRegistry meters;
    private final JudgeVersions versions;

    GeminiJudge(
            VendorEndpoint endpoint,
            PromptFiles.Loaded files,
            VendorTransport transport,
            VendorLimits limits,
            UsageSink usage,
            VendorGuard guard,
            MeterRegistry meters) {
        this.endpoint = endpoint;
        this.files = files;
        this.schema =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7).getSchema(files.schema());
        this.transport = transport;
        this.limits = limits;
        this.usage = usage;
        this.guard = guard;
        this.meters = meters;
        this.versions = new JudgeVersions(endpoint.model(), files.promptVersion(), files.schemaVersion());
    }

    @Override
    public JudgeVersions versions() {
        return versions;
    }

    @Override
    public JudgeLabels judge(JudgeInput input) {
        GeminiInput.Rendered sent = GeminiInput.render(input);
        byte[] body = GeminiRequest.body(files, sent, limits.geminiThinkingBudgetTokens());
        return guard.call(() -> judgeWithRetry(body, sent));
    }

    private JudgeLabels judgeWithRetry(byte[] body, GeminiInput.Rendered sent) {
        for (int attempt = 1; ; attempt++) {
            usage.starting(Vendor.GEMINI, 0);
            VendorTransport.Reply reply;
            try {
                reply = transport.post(
                        GeminiRequest.uri(endpoint.baseUrl(), endpoint.model()),
                        Map.of(GeminiRequest.KEY_HEADER, endpoint.apiKey()),
                        GeminiRequest.CONTENT_TYPE,
                        body,
                        VendorBudget.remainingMs(limits.budgetMs()));
            } catch (VendorTransport.Failure failure) {
                usage.noUsageReported(Vendor.GEMINI);
                throw failed(failure.kind());
            }
            if (reply.status() < OK_FROM || reply.status() >= OK_BELOW) {
                usage.noUsageReported(Vendor.GEMINI);
                throw httpFailure(reply.status());
            }
            GeminiAnswer.Parsed parsed = GeminiAnswer.parse(reply.body(), schema, sent);
            if (parsed.hasUsage()) {
                usage.used(VendorUsage.tokens(parsed.inputTokens(), parsed.outputTokens()));
            } else {
                usage.noUsageReported(Vendor.GEMINI);
            }
            if (parsed.labels() != null) {
                count("vendor.call", "ok");
                checkModel(parsed.answeredModel());
                return parsed.labels();
            }
            count("vendor.call", "invalid_output");
            if (attempt < MAX_ATTEMPTS && VendorBudget.remainingMs(limits.budgetMs()) >= limits.retryMinRemainingMs()) {
                count("vendor.retry", "invalid_output");
                continue;
            }
            throw new VendorUnavailable("llm_invalid_output");
        }
    }

    private VendorUnavailable httpFailure(int status) {
        String code = status >= SERVER_ERROR_FROM
                ? "llm_http_5xx"
                : status == TOO_MANY_REQUESTS ? "llm_http_429" : "llm_http_4xx";
        count("vendor.call", code);
        return new VendorUnavailable(code);
    }

    private VendorUnavailable failed(VendorTransport.Kind kind) {
        String outcome = switch (kind) {
            case TIMEOUT -> "timeout";
            case NETWORK -> "network";
            case INTERRUPTED -> "interrupted";
        };
        count("vendor.call", outcome);
        return new VendorUnavailable("llm_" + outcome);
    }

    /** The pinned id must equal the id Gemini answers with; a change is counted and logged (ids only, no words). */
    private void checkModel(@Nullable String answered) {
        if (answered != null && !answered.equals(endpoint.model())) {
            meters.counter("vendor.model.mismatch", "vendor", Vendor.GEMINI.tag())
                    .increment();
            LOG.warn(
                    "vendor.model_mismatch vendor={} pinned={} answered={}",
                    Vendor.GEMINI.tag(),
                    endpoint.model(),
                    answered);
        }
    }

    private void count(String name, String outcome) {
        meters.counter(name, "vendor", Vendor.GEMINI.tag(), "outcome", outcome).increment();
    }
}
