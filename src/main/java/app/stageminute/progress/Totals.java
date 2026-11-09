package app.stageminute.progress;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The totals line "48 sessions · 39 min" (03 F-055): sessions with a delivered result, and the minutes spoken in
 * them (rounded to the nearest minute). They come from the session rows, so deleting a report never shrinks them.
 */
@Schema(name = "ProgressTotals")
public record Totals(
        int sessions, @JsonProperty("speaking_minutes") int speakingMinutes) {}
