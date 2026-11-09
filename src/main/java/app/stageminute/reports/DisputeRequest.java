package app.stageminute.reports;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * The body of {@code POST /v1/reports/{analysisId}/dispute} (02b F-042, C-67). It carries no fix id and no version:
 * those are read from the stored report (AD-147).
 *
 * @param area what looks wrong
 * @param index the step's position or the suggestion's id, for {@code structure_step} and {@code
 *     language_suggestion} only
 * @param note the person's note, at most {@code dispute_note_max_chars} (300) characters; never with {@code fix_first}
 * @param transcript the take's words, sent only when the person ticked the box; never with {@code fix_first}
 */
@Schema(name = "DisputeRequest")
public record DisputeRequest(
        DisputeArea area,

        @Nullable
        @Schema(
                nullable = true,
                minimum = "0",
                description = "Step position (0 to 3) for structure_step; the suggestion's id for language_suggestion;"
                        + " absent for every other area")
        Integer index,

        @Nullable @Schema(nullable = true, maxLength = 300, description = "Optional; never with fix_first")
        String note,

        @Nullable
        @Schema(
                nullable = true,
                description = "The take's words, only when the box was ticked (kept 90 days); never with fix_first")
        String transcript) {

    /**
     * The request's words never reach a log line (05 F-081): this record prints only its area and whether a note or
     * a transcript was sent.
     */
    @Override
    public String toString() {
        return "DisputeRequest[area=" + area + ", index=" + index + ", note=" + (note != null) + ", transcript="
                + (transcript != null) + "]";
    }
}
