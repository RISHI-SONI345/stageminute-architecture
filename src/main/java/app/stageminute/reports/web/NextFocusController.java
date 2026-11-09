package app.stageminute.reports.web;

import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.CurrentUser;
import app.stageminute.platform.Reason;
import app.stageminute.reports.CarriedFocusService;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/me/next-focus/dismiss} ("Not today", 03 F-034; 06-API section 3): clears the carried focus that
 * came from the named session. {@code 204} with no body, also when there is nothing to clear, so it is safe to repeat.
 */
@Tag(name = "me")
@RestController
class NextFocusController {

    record DismissRequest(
            @Nullable
            @Schema(description = "The session the focus came from (carried_focus or next_focus)")
            @JsonProperty("from_session_id")
            String fromSessionId) {}

    private final CurrentUser currentUser;
    private final CarriedFocusService carriedFocus;

    NextFocusController(CurrentUser currentUser, CarriedFocusService carriedFocus) {
        this.currentUser = currentUser;
        this.carriedFocus = carriedFocus;
    }

    @Operation(operationId = "dismissNextFocus", summary = "Not today: clear the carried focus from this session")
    @PostMapping(path = "/v1/me/next-focus/dismiss", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> dismiss(@RequestBody DismissRequest request) {
        carriedFocus.dismiss(currentUser.require(), sessionId(request.fromSessionId()));
        return ResponseEntity.noContent().build();
    }

    private static UUID sessionId(@Nullable String id) {
        try {
            if (id != null) {
                return UUID.fromString(id);
            }
        } catch (IllegalArgumentException e) {
            // falls through to the refusal
        }
        throw new BusinessRefusal(Reason.VALIDATION_FAILED, Map.of("fields", List.of("from_session_id")));
    }
}
