package app.stageminute.reports.web;

import app.stageminute.account.ProfileService;
import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.CurrentUser;
import app.stageminute.platform.Reason;
import app.stageminute.platform.UserId;
import app.stageminute.reports.Patterns;
import app.stageminute.reports.Reports;
import app.stageminute.reports.internal.PatternsCalculator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/me/patterns?style=prep} (06-API section 7; 03 F-056): the two pattern cards of one style, from the
 * facts of the last first takes. Without {@code style} the person's current style is used. Numbers and step codes
 * only; it never reads the person's plan.
 */
@Tag(name = "patterns")
@RestController
@RequestMapping(path = "/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
class PatternsController {

    private final CurrentUser currentUser;
    private final Reports reports;
    private final ProfileService profiles;

    PatternsController(CurrentUser currentUser, Reports reports, ProfileService profiles) {
        this.currentUser = currentUser;
        this.reports = reports;
        this.profiles = profiles;
    }

    @Operation(operationId = "getPatterns", summary = "Strength, opportunity and second-attempt fixes of one style")
    @GetMapping("/patterns")
    Patterns get(
            @RequestParam(name = "style", required = false) @Schema(allowableValues = {"prep", "star"}) @Nullable
                    String style) {
        UserId user = currentUser.require();
        String chosen = style != null ? style : profiles.get(user).style();
        if (!PatternsCalculator.knownStyle(chosen)) {
            throw new BusinessRefusal(Reason.VALIDATION_FAILED, Map.of("fields", List.of("style")));
        }
        return reports.patterns(user, chosen);
    }
}
