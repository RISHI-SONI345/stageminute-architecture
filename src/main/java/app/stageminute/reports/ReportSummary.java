package app.stageminute.reports;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of the history list ({@code GET /v1/analyses}, 06-API section 7; 03 F-057): numbers and ids only. The app
 * shows the topic's words from its own topic pool by {@code topic_id} (the reports module does not read the topic
 * catalog, AD-130).
 */
@Schema(name = "AnalysisSummary")
public record ReportSummary(
        @JsonProperty("analysis_id") UUID analysisId,
        @JsonProperty("session_id") UUID sessionId,
        int attempt,
        @JsonProperty("topic_id") String topicId,
        @Schema(allowableValues = {"prep", "star"}) String style,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("duration_ms") int durationMs,
        @Schema(allowableValues = {"ok", "low_audio"}) String status,
        @JsonProperty("score_total") int scoreTotal,

        @JsonProperty("dispute_state")
        @Nullable
        @Schema(
                nullable = true,
                allowableValues = {"sent"})
        String disputeState,

        @JsonProperty("can_continue")
        @Schema(
                description =
                        "True while this is the session's last attempt and its account day is open") // copy-ok: API
        boolean canContinue) {

    /** The same row with the open-session answer the service worked out. */
    public ReportSummary withCanContinue(boolean value) {
        return new ReportSummary(
                analysisId,
                sessionId,
                attempt,
                topicId,
                style,
                createdAt,
                durationMs,
                status,
                scoreTotal,
                disputeState,
                value);
    }
}
