package app.stageminute.progress.web;

import app.stageminute.platform.CurrentUser;
import app.stageminute.progress.Progress;
import app.stageminute.progress.ProgressSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/me/progress} (the Progress tab in one call, 03 F-053, F-055) and {@code POST /v1/me/seen/{marker}}
 * (06-API section 3: a milestone celebration was shown). Neither reads the person's plan (AD-155).
 */
@Tag(name = "progress")
@RestController
@RequestMapping(path = "/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
class ProgressController {

    private final CurrentUser currentUser;
    private final Progress progress;

    ProgressController(CurrentUser currentUser, Progress progress) {
        this.currentUser = currentUser;
        this.progress = progress;
    }

    @Operation(
            operationId = "getProgress",
            summary = "Streak, week strip, totals, pending milestone and today's account date") // copy-ok: API
    @GetMapping("/progress")
    ProgressSnapshot get() {
        return progress.snapshot(currentUser.require());
    }

    @Operation(
            operationId = "markSeen",
            summary = "A milestone celebration was shown (milestone_3 to milestone_100); safe to repeat; "
                    + "400 validation_failed for any other word or a milestone not reached") // copy-ok: API
    // description for
    // developers
    @PostMapping("/seen/{marker}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void seen(@PathVariable String marker) {
        progress.markSeen(currentUser.require(), marker);
    }
}
