package app.stageminute.analysis;

import app.stageminute.reports.ReportV2;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * The answer of {@code POST /v1/analyze} (06-API section 6): the report, and the take's words once. {@code speech_data}
 * is null when the answer is a replay after a restart or after the 10-minute retry window (F-091; the app's Your words
 * then says "not available for this take"). The stored copy of this answer (the idempotency entry) never holds it.
 *
 * @param report Report v2
 * @param speechData the take's words, or null
 */
@Schema(name = "AnalyzeResponse")
public record AnalysisOutcome(
        ReportV2 report,

        @JsonProperty("speech_data")
        @Nullable
        @Schema(nullable = true, description = "Returned once; absent on a replay after a restart (F-091)")
        SpeechDataView speechData) {

    /** The same answer without the words: what the idempotency entry stores. */
    public AnalysisOutcome withoutSpeech() {
        return new AnalysisOutcome(report, null);
    }
}
