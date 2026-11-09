package app.stageminute.practice.web;

import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.CurrentUser;
import app.stageminute.platform.Reason;
import app.stageminute.practice.internal.NextTopicService;
import app.stageminute.topics.Level;
import app.stageminute.topics.Style;
import app.stageminute.topics.Topic;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.Explode;
import io.swagger.v3.oas.annotations.enums.ParameterStyle;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/topics/next} (06-API section 4, F-014, AD-102). It sits in {@code practice} because the pick needs
 * the person's sessions, which {@code topics} cannot read. The pool endpoint stays in {@code topics}.
 */
@Tag(name = "topics")
@RestController
@RequestMapping(path = "/v1/topics", produces = MediaType.APPLICATION_JSON_VALUE)
class NextTopicController {

    /** The same fields as a pool entry. Its own record: the pool's is private to {@code topics}. */
    record NextTopicView(
            String id,
            String text,

            @JsonProperty("role_context")
            @Nullable
            @Schema(
                    nullable = true,
                    description =
                            "STAR only: who asks, such as \"Your team lead asks:\"") // copy-ok: API description for
            // developers
            String roleContext,

            int difficulty,
            @JsonProperty("think_s") int thinkS,
            @JsonProperty("speak_s") int speakS,
            boolean seed) {}

    record NextTopicResponse(
            @Nullable @Schema(nullable = true, description = "Null only when this style and level have no live topic")
            NextTopicView topic) {}

    private final CurrentUser currentUser;
    private final NextTopicService service;

    NextTopicController(CurrentUser currentUser, NextTopicService service) {
        this.currentUser = currentUser;
        this.service = service;
    }

    @Operation(operationId = "getNextTopic", summary = "The next topic on the difficulty ramp")
    @GetMapping("/next")
    NextTopicResponse next(
            @RequestParam(required = false) @Nullable @Schema(allowableValues = {"prep", "star"}) String style,
            @RequestParam(required = false)
                    @Nullable
                    @Schema(allowableValues = {"starter", "elementary", "intermediate", "upper_intermediate"})
                    String level,
            @RequestParam(required = false)
                    @Nullable
                    @Parameter(
                            description =
                                    "Topic ids the phone showed lately, oldest first, comma separated", // copy-ok: API
                            // description
                            // for
                            // developers
                            style = ParameterStyle.FORM,
                            explode = Explode.FALSE)
                    List<String> exclude) {
        Style wantedStyle = style == null ? null : Style.fromWire(style).orElse(null);
        Level wantedLevel = level == null ? null : Level.fromWire(level).orElse(null);
        if (wantedStyle == null || wantedLevel == null) {
            throw new BusinessRefusal(Reason.VALIDATION_FAILED);
        }
        List<String> shown = exclude == null
                ? List.of()
                : exclude.stream().filter(id -> !id.isBlank()).toList();
        return new NextTopicResponse(service.next(currentUser.require(), wantedStyle, wantedLevel, shown)
                .map(NextTopicController::view)
                .orElse(null));
    }

    private static NextTopicView view(Topic t) {
        return new NextTopicView(t.id(), t.text(), t.roleContext(), t.difficulty(), t.thinkS(), t.speakS(), t.seed());
    }
}
