package app.stageminute.analysis.web;

import app.stageminute.analysis.AnalysisOutcome;
import app.stageminute.analysis.AnalyzeCommand;
import app.stageminute.analysis.Analyzer;
import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.CurrentUser;
import app.stageminute.platform.Reason;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /v1/analyze} (06-API section 6; 03 F-026, F-091). Multipart: {@code audio} (AAC/M4A, at most {@code
 * upload_max_bytes}) and {@code meta} (JSON). The {@code Idempotency-Key} header is the take's key and must equal
 * {@code meta.take_key}. The audio stays in memory for this request; the body is never logged (AD-23). Only the
 * session id and the phone's length are read from {@code meta}: {@code hit_cap}, {@code interrupted} and {@code
 * quiet_hint} are the phone's view and the server decides each itself (C-69).
 */
@Tag(name = "analyze")
@RestController
class AnalyzeController {

    /** Letters, digits, {@code _} and {@code -}, the same rule as the take key of {@code POST /v1/sessions}. */
    private static final Pattern TAKE_KEY = Pattern.compile("[A-Za-z0-9_-]{1,100}");

    @Schema(name = "AnalyzeMetaRequest", description = "The meta part of POST /v1/analyze")
    record Meta(
            @JsonProperty("session_id") @Nullable UUID sessionId,
            @JsonProperty("take_key") @Nullable String takeKey,

            @JsonProperty("duration_ms") @Nullable @Schema(description = "The phone's length: a hint only (C-69)")
            Integer durationMs,

            @JsonProperty("hit_cap") @Nullable Boolean hitCap,
            @Nullable Boolean interrupted,
            @JsonProperty("quiet_hint") @Nullable Boolean quietHint) {}

    private final CurrentUser currentUser;
    private final Analyzer analyzer;
    private final JsonMapper json;

    AnalyzeController(CurrentUser currentUser, Analyzer analyzer, JsonMapper json) {
        this.currentUser = currentUser;
        this.analyzer = analyzer;
        this.json = json;
    }

    @Operation(summary = "Analyse one take: Report v2, and the take's words once")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Report v2 (also for a take with no score), and the take's words once",
                useReturnTypeSchema = true),
        @ApiResponse(
                responseCode = "403",
                description = "consent_off",
                content =
                        @Content(
                                mediaType = "application/problem+json",
                                schema = @Schema(ref = "#/components/schemas/Problem"))),
        @ApiResponse(
                responseCode = "404",
                description = "not_found: an unknown session or someone else's",
                content =
                        @Content(
                                mediaType = "application/problem+json",
                                schema = @Schema(ref = "#/components/schemas/Problem"))),
        @ApiResponse(
                responseCode = "409",
                description = "attempt_not_allowed, with next_attempt (session_ended, attempt_cap, focus_done)",
                content =
                        @Content(
                                mediaType = "application/problem+json",
                                schema = @Schema(ref = "#/components/schemas/Problem"))),
        @ApiResponse(
                responseCode = "413",
                description = "file_too_large, with max_bytes",
                content =
                        @Content(
                                mediaType = "application/problem+json",
                                schema = @Schema(ref = "#/components/schemas/Problem"))),
        @ApiResponse(
                responseCode = "415",
                description = "unsupported_format: not an AAC/M4A file",
                content =
                        @Content(
                                mediaType = "application/problem+json",
                                schema = @Schema(ref = "#/components/schemas/Problem"))),
        @ApiResponse(
                responseCode = "422",
                description = "too_short: under 20 s by the server's measure",
                content =
                        @Content(
                                mediaType = "application/problem+json",
                                schema = @Schema(ref = "#/components/schemas/Problem"))),
        @ApiResponse(
                responseCode = "429",
                description = "rate_limited, with retry_after_s: the same take is being analysed",
                content =
                        @Content(
                                mediaType = "application/problem+json",
                                schema = @Schema(ref = "#/components/schemas/Problem"))),
        @ApiResponse(
                responseCode = "502",
                description = "analysis_unavailable, with session_given_back",
                content =
                        @Content(
                                mediaType = "application/problem+json",
                                schema = @Schema(ref = "#/components/schemas/Problem")))
    })
    @PostMapping(
            path = "/v1/analyze",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    AnalysisOutcome analyze(
            @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String idempotencyKey,
            @RequestHeader(name = "X-Device-Id", required = false) @Nullable String deviceId,
            @RequestPart(name = "audio", required = false) @Nullable MultipartFile audio,
            @RequestPart(name = "meta", required = false) @Schema(implementation = Meta.class) @Nullable String meta)
            throws IOException {
        Meta parsed = parse(meta);
        List<String> wrong = new ArrayList<>();
        if (audio == null || audio.isEmpty()) {
            wrong.add("audio");
        }
        if (parsed.sessionId() == null) {
            wrong.add("session_id");
        }
        if (parsed.takeKey() == null || !TAKE_KEY.matcher(parsed.takeKey()).matches()) {
            wrong.add("take_key");
        } else if (!parsed.takeKey().equals(idempotencyKey)) {
            wrong.add("Idempotency-Key");
        }
        if (parsed.durationMs() == null || parsed.durationMs() < 0) {
            wrong.add("duration_ms");
        }
        if (!wrong.isEmpty() || audio == null || parsed.sessionId() == null || parsed.takeKey() == null) {
            throw new BusinessRefusal(Reason.VALIDATION_FAILED, Map.of("fields", wrong));
        }
        return analyzer.analyze(new AnalyzeCommand(
                currentUser.require(),
                parsed.takeKey(),
                parsed.sessionId(),
                audio.getBytes(),
                parsed.durationMs(),
                deviceId));
    }

    private Meta parse(@Nullable String meta) {
        if (meta == null || meta.isBlank()) {
            return new Meta(null, null, null, null, null, null);
        }
        try {
            return json.readValue(meta, Meta.class);
        } catch (JacksonException e) {
            throw new BusinessRefusal(Reason.VALIDATION_FAILED, Map.of("fields", List.of("meta")));
        }
    }
}
