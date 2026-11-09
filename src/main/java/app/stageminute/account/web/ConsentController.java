package app.stageminute.account.web;

import app.stageminute.account.ConsentService;
import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.CurrentUser;
import app.stageminute.platform.Reason;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code PUT /v1/me/consent} (06-API section 3, F-079): the person's answer on the consent screen, or a later withdrawal. */
@Tag(name = "me")
@RestController
@RequestMapping("/v1/me/consent")
class ConsentController {

    /**
     * {@code ai} and {@code policy_version} are required (checked below, so a missing one gets a reason code
     * and its field name); {@code usage_data} may be left out and then stays as it is.
     */
    record ConsentRequest(
            @Nullable Boolean ai,
            @Nullable @JsonProperty("policy_version") String policyVersion,
            @Nullable @JsonProperty("usage_data") Boolean usageData) {}

    private final CurrentUser currentUser;
    private final ConsentService consents;

    ConsentController(CurrentUser currentUser, ConsentService consents) {
        this.currentUser = currentUser;
        this.consents = consents;
    }

    @Operation(operationId = "putConsent", summary = "Agree to, or withdraw, AI analysis of takes")
    @ApiResponse(responseCode = "204", description = "Saved")
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> put(@RequestBody ConsentRequest request) {
        List<String> missing = new ArrayList<>();
        if (request.ai() == null) {
            missing.add("ai");
        }
        String version = request.policyVersion();
        if (version == null || version.isBlank()) {
            missing.add("policy_version");
        }
        if (!missing.isEmpty()) {
            throw new BusinessRefusal(Reason.VALIDATION_FAILED, Map.of("fields", List.copyOf(missing)));
        }
        var user = currentUser.require();
        boolean usageData = request.usageData() != null
                ? request.usageData()
                : consents.state(user).usageData();
        consents.record(user, version, request.ai(), usageData);
        return ResponseEntity.noContent().build();
    }
}
