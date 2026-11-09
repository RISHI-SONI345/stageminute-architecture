package app.stageminute.analysis;

import app.stageminute.account.ConsentService;
import app.stageminute.analysis.internal.AnalysisRun;
import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.IdempotencyStore;
import app.stageminute.platform.Reason;
import app.stageminute.platform.SettingKey;
import app.stageminute.platform.Settings;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /v1/analyze} as one method (02-SERVER-LLD.md, analysis; 04-FLOWS.md "Analyze"; 03 F-091). It checks
 * consent, the size and the format, makes the request safe to repeat (the same take key and the same body get the same
 * stored answer for {@code idempotency_ttl_minutes}, with the words while the in-memory copy lasts), and hands the take
 * to {@link AnalysisRun}, which measures, analyses, saves and commits. A refusal is a {@link BusinessRefusal} with a
 * reason code; a failed run is retried by the same request only, never by the server (F-122).
 */
@Service
public class Analyzer {

    private static final String KEY_PREFIX = "analyze:";
    private static final int RETRY_AFTER_S = 5;

    private final ConsentService consents;
    private final IdempotencyStore idempotency;
    private final Settings settings;
    private final AnalysisRun run;
    private final JsonMapper json;

    Analyzer(
            ConsentService consents,
            IdempotencyStore idempotency,
            Settings settings,
            AnalysisRun run,
            JsonMapper json) {
        this.consents = consents;
        this.idempotency = idempotency;
        this.settings = settings;
        this.run = run;
        this.json = json;
    }

    /**
     * Analyses one take, or replays the stored answer of the same request.
     *
     * @throws BusinessRefusal {@code consent_off}, {@code file_too_large} (with {@code max_bytes}), {@code
     *     unsupported_format}, {@code too_short}, {@code validation_failed}, {@code not_found}, {@code
     *     attempt_not_allowed}, {@code rate_limited} (another try of the same take is running) or {@code
     *     analysis_unavailable} (with {@code session_given_back})
     */
    public AnalysisOutcome analyze(AnalyzeCommand command) {
        consents.require(command.user());
        int maxBytes = settings.get(SettingKey.UPLOAD_MAX_BYTES);
        if (command.audio().length > maxBytes) {
            throw new BusinessRefusal(Reason.FILE_TOO_LARGE, Map.of("max_bytes", maxBytes));
        }
        String key = KEY_PREFIX + command.takeKey();
        IdempotencyStore.Begin begin = idempotency.begin(command.user().value(), key, fingerprint(command));
        switch (begin.outcome()) {
            case DONE -> {
                AnalysisOutcome stored = json.readValue(begin.response(), AnalysisOutcome.class);
                return new AnalysisOutcome(
                        stored.report(),
                        run.cachedSpeech(command.user(), command.takeKey()).orElse(null));
            }
            case IN_PROGRESS -> throw new BusinessRefusal(Reason.RATE_LIMITED, Map.of("retry_after_s", RETRY_AFTER_S));
            case CONFLICT -> throw new BusinessRefusal(Reason.VALIDATION_FAILED, Map.of("fields", List.of("take_key")));
            case STARTED -> {
                // the run below
            }
        }
        try {
            return run.analyze(
                    command,
                    stored -> idempotency.complete(
                            command.user().value(), key, json.writeValueAsString(stored.withoutSpeech())));
        } catch (RuntimeException e) {
            idempotency.fail(command.user().value(), key); // the same request may be sent again
            throw e;
        }
    }

    /** The same take sent again is the same request: the session, the phone's length and the audio. */
    private static byte[] fingerprint(AnalyzeCommand command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(command.sessionId().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            digest.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(command.phoneDurationMs())
                    .array());
            digest.update(command.audio());
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
