package app.stageminute.practice.web;

import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.CurrentUser;
import app.stageminute.platform.IdempotencyStore;
import app.stageminute.platform.Reason;
import app.stageminute.platform.SpendGate;
import app.stageminute.platform.UserId;
import app.stageminute.practice.Practice;
import app.stageminute.practice.QuotaState;
import app.stageminute.practice.Reservation;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /v1/sessions} and {@code POST /v1/sessions/{id}/release} (06-API section 5, 03 F-048 to F-052).
 * The phone calls the first after the recorder opened (D-66). It is safe to repeat per take: the idempotency
 * entry answers a quick retry with the stored answer, and {@code UNIQUE (user_id, take_key)} returns the same
 * session even after the entry expired (AD-106). A refusal is a problem with the reason {@code quota_zero}
 * (403) or {@code pro_cap} (429) and {@code resets_at}; it never carries a gate, an ad or a Pro offer (F-051).
 */
@Tag(name = "sessions")
@RestController
@RequestMapping(path = "/v1/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
class SessionsController {

    /** Letters, digits, {@code _} and {@code -}; the phone makes it (for example {@code t_01JAZ...}). */
    private static final Pattern TAKE_KEY = Pattern.compile("[A-Za-z0-9_-]{1,100}");

    private static final String IDEMPOTENCY_PREFIX = "sessions:";

    record StartRequest(
            @JsonProperty("take_key") @Nullable @Schema(description = "The first take's key; also the Idempotency-Key")
            String takeKey,

            @JsonProperty("topic_id") @Nullable String topicId) {}

    record SessionStarted(
            @JsonProperty("session_id") UUID sessionId,

            @Schema(description = "Always 1: the session starts with its first attempt")
            int attempt,

            @Schema(allowableValues = {"free", "rewarded", "pro"})
            String source,

            @JsonProperty("quota_state")
            @Schema(
                    allowableValues = {"ok"},
                    description =
                            "The same words as the quota block of /me; any other state is a problem with that reason")
            String quotaState) {}

    record ReleaseRequest(
            @Nullable @Schema(allowableValues = {"discard", "cancel", "abandon", "no_local_take"})
            String reason) {}

    private final CurrentUser currentUser;
    private final Practice practice;
    private final IdempotencyStore idempotency;
    private final JsonMapper json;

    SessionsController(CurrentUser currentUser, Practice practice, IdempotencyStore idempotency, JsonMapper json) {
        this.currentUser = currentUser;
        this.practice = practice;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Operation(operationId = "startSession", summary = "Reserve today's session for the first take of a topic")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SessionStarted> start(
            @RequestBody StartRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String idempotencyKey,
            @Parameter(hidden = true) @RequestHeader(name = "X-Device-Id", required = false) @Nullable
                    String deviceId) {
        String takeKey = request.takeKey();
        String topicId = request.topicId();
        if (takeKey == null || !TAKE_KEY.matcher(takeKey).matches() || topicId == null || topicId.isBlank()) {
            throw new BusinessRefusal(Reason.VALIDATION_FAILED);
        }
        if (idempotencyKey != null && !idempotencyKey.equals(takeKey)) {
            throw new BusinessRefusal(Reason.VALIDATION_FAILED);
        }
        UserId user = currentUser.require();
        String key = IDEMPOTENCY_PREFIX + takeKey;
        IdempotencyStore.Begin begin = idempotency.begin(user.value(), key, hash(takeKey, topicId));
        switch (begin.outcome()) {
            case DONE -> {
                return created(json.readValue(begin.response(), SessionStarted.class));
            }
            case CONFLICT -> throw new BusinessRefusal(Reason.VALIDATION_FAILED); // same take, another topic
            case IN_PROGRESS -> {
                return created(reserve(user, takeKey, topicId, deviceId)); // safe: the unique key counts it once
            }
            case STARTED -> {
                try {
                    SessionStarted started = reserve(user, takeKey, topicId, deviceId);
                    idempotency.complete(user.value(), key, json.writeValueAsString(started));
                    return created(started);
                } catch (RuntimeException e) {
                    idempotency.fail(user.value(), key); // a refusal is not stored: a later try may pass
                    throw e;
                }
            }
        }
        throw new IllegalStateException("unknown idempotency outcome " + begin.outcome());
    }

    @Operation(
            operationId = "releaseSession",
            // copy-ok: API description for developers
            summary = "Give back a reserved session the person ended; ignored after commit or a second time")
    @PostMapping(path = "/{id}/release", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> release(@PathVariable("id") String id, @RequestBody ReleaseRequest request) {
        Practice.ReleaseReason reason =
                releaseReason(request.reason()).orElseThrow(() -> new BusinessRefusal(Reason.VALIDATION_FAILED));
        UUID sessionId = parseId(id);
        practice.release(currentUser.require(), sessionId, reason);
        return ResponseEntity.noContent().build();
    }

    private SessionStarted reserve(UserId user, String takeKey, String topicId, @Nullable String deviceId) {
        Reservation reservation = practice.reserve(user, takeKey, topicId, deviceId);
        if (reservation.state() != QuotaState.OK || reservation.sessionId() == null || reservation.source() == null) {
            if (reservation.state() == QuotaState.THROTTLED) {
                // 03 F-087: the AI-cost pause; the board shows its end in the person's own time, from the server.
                throw SpendGate.throttled(reservation.resetsAt(), Map.of());
            }
            String resetsAt = reservation.resetsAt().toString();
            Reason reason = reservation.state() == QuotaState.PRO_CAP ? Reason.PRO_CAP : Reason.QUOTA_ZERO;
            throw new BusinessRefusal(reason, Map.of("resets_at", resetsAt));
        }
        return new SessionStarted(
                reservation.sessionId(), 1, reservation.source().wire(), QuotaState.OK.wire());
    }

    /** Only what the app may send; {@code no_score} is the server's own reason (F-037). */
    private static Optional<Practice.ReleaseReason> releaseReason(@Nullable String wire) {
        for (Practice.ReleaseReason reason : Practice.ReleaseReason.values()) {
            if (reason != Practice.ReleaseReason.NO_SCORE && reason.wire().equals(wire)) {
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }

    private static UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new BusinessRefusal(Reason.VALIDATION_FAILED);
        }
    }

    private static ResponseEntity<SessionStarted> created(SessionStarted started) {
        return ResponseEntity.status(HttpStatus.CREATED).body(started);
    }

    private static byte[] hash(String takeKey, String topicId) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest((takeKey + "\n" + topicId).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
