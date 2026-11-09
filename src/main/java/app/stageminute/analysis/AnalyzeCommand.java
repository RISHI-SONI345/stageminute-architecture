package app.stageminute.analysis;

import app.stageminute.platform.UserId;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One {@code POST /v1/analyze} request (06-API section 6). The audio is in memory for this request only (rule 4); the
 * phone's length is a hint the server compares with its own measure, never uses (C-69).
 *
 * @param user who sent it
 * @param takeKey the take's key, also the Idempotency-Key
 * @param sessionId the session the take belongs to
 * @param audio the AAC/M4A bytes
 * @param phoneDurationMs the length the phone measured (a hint)
 * @param deviceId the phone's {@code X-Device-Id}, or null
 */
@SuppressWarnings("ArrayRecordComponent") // the audio is read once and never compared
public record AnalyzeCommand(
        UserId user,
        String takeKey,
        UUID sessionId,
        byte[] audio,
        int phoneDurationMs,
        @Nullable String deviceId) {

    public AnalyzeCommand {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(takeKey, "takeKey");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(audio, "audio");
    }

    @Override
    public String toString() {
        return "AnalyzeCommand[" + audio.length + " bytes]";
    }
}
