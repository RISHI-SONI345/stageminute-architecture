package app.stageminute.reports.web;

import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.CurrentUser;
import app.stageminute.platform.Reason;
import app.stageminute.reports.ReportSummary;
import app.stageminute.reports.ReportV2;
import app.stageminute.reports.Reports;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/analyses} and {@code GET /v1/analyses/{id}} (06-API section 7; 03 F-057): the reports of the last 7
 * days, and one of them; {@code DELETE /v1/analyses/{id}} (02b F-046) removes one. A report is never served with {@code speech_data} (it is not kept); an older report, an
 * unknown id and someone else's id are all {@code 404 not_found}, so an id tells nobody whether it exists.
 */
@Tag(name = "analyses")
@RestController
@RequestMapping(path = "/v1/analyses", produces = MediaType.APPLICATION_JSON_VALUE)
class AnalysesController {

    /** The only window at launch (F-057; the history screen is Later). */
    private static final String WINDOW = "7d";

    record AnalysesResponse(List<ReportSummary> items) {}

    private final CurrentUser currentUser;
    private final Reports reports;

    AnalysesController(CurrentUser currentUser, Reports reports) {
        this.currentUser = currentUser;
        this.reports = reports;
    }

    @Operation(summary = "The person's reports of the last 7 days, newest first")
    @GetMapping
    AnalysesResponse list(
            @RequestParam(name = "window", required = false) @Schema(allowableValues = {"7d"}) @Nullable
                    String window) {
        if (window != null && !WINDOW.equals(window)) {
            throw new BusinessRefusal(Reason.VALIDATION_FAILED, Map.of("fields", List.of("window")));
        }
        return new AnalysesResponse(reports.recent(currentUser.require()));
    }

    @Operation(summary = "One report of the last 7 days, without speech_data")
    @ApiResponse(responseCode = "200", description = "Report v2", useReturnTypeSchema = true)
    @ApiResponse(
            responseCode = "404",
            description = "not_found: older than 7 days, unknown, or someone else's",
            content =
                    @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/Problem")))
    @GetMapping("/{id}")
    ReportV2 get(@PathVariable UUID id) {
        return reports.get(currentUser.require(), id).orElseThrow(() -> new BusinessRefusal(Reason.NOT_FOUND));
    }

    @Operation(summary = "Delete one report with its facts, disputes and carried focus; totals and streak stay")
    @ApiResponse(responseCode = "204", description = "Gone, or never there: the same answer for every id")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        reports.delete(currentUser.require(), id);
    }
}
