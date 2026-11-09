package app.stageminute.reports.web;

import app.stageminute.platform.CurrentUser;
import app.stageminute.reports.DisputeRequest;
import app.stageminute.reports.Disputes;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/reports/{analysisId}/dispute} (06-API section 7; 02b F-042, C-67; AD-147): "What looks wrong?" and
 * the one-tap "This fix felt wrong". The score never changes (D-84).
 */
@Tag(name = "reports")
@RestController
@RequestMapping(path = "/v1/reports", produces = MediaType.APPLICATION_JSON_VALUE)
class DisputesController {

    /** The answer: the report now shows "Sent for review". */
    @Schema(name = "DisputeAccepted")
    record DisputeAccepted(
            @JsonProperty("dispute_state") @Schema(allowableValues = {"sent"})
            String disputeState) {}

    private static final DisputeAccepted SENT = new DisputeAccepted("sent");

    private final CurrentUser currentUser;
    private final Disputes disputes;

    DisputesController(CurrentUser currentUser, Disputes disputes) {
        this.currentUser = currentUser;
        this.disputes = disputes;
    }

    @Operation(summary = "Tell us what looks wrong with a report (5 a day); the score never changes")
    @ApiResponse(
            responseCode = "202",
            description = "Kept; dispute_state is now sent", // copy-ok: API description for developers
            useReturnTypeSchema = true)
    @ApiResponse(
            responseCode = "400",
            description = "validation_failed (fields): a wrong area or index, a note too long, words on a one-tap",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/Problem")))
    @ApiResponse(
            responseCode = "404",
            description = "not_found: older than 7 days, unknown, deleted, or someone else's",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/Problem")))
    @ApiResponse(
            responseCode = "429",
            description = "rate_limited (retry_after_s): 5 disputes today",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/Problem")))
    @PostMapping(path = "/{analysisId}/dispute", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    DisputeAccepted dispute(@PathVariable UUID analysisId, @RequestBody DisputeRequest request) {
        disputes.send(currentUser.require(), analysisId, request);
        return SENT;
    }
}
