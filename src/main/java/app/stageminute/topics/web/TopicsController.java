package app.stageminute.topics.web;

import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.Reason;
import app.stageminute.topics.Level;
import app.stageminute.topics.Style;
import app.stageminute.topics.Topic;
import app.stageminute.topics.TopicCatalog;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * {@code GET /v1/topics/pool} (06-API section 4, F-014). The phone keeps the pool and spins from it, also offline;
 * the ETag is the catalog version, so an unchanged catalog answers {@code 304} with no body. {@code GET
 * /topics/next} is not here: it lives in {@code practice} (AD-102).
 */
@Tag(name = "topics")
@RestController
@RequestMapping(path = "/v1/topics", produces = MediaType.APPLICATION_JSON_VALUE)
class TopicsController {

    /** What the phone needs to show and run a topic. A retired topic is never listed, so there is no retired flag. */
    record TopicView(
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

            @Schema(description = "The easy topic for the first spin ever; the phone takes it when it has never spun")
            boolean seed) {}

    record PoolResponse(
            @Schema(description = "The catalog version; also the ETag")
            String version,

            List<TopicView> topics) {}

    private final TopicCatalog catalog;

    TopicsController(TopicCatalog catalog) {
        this.catalog = catalog;
    }

    @Operation(operationId = "getTopicPool", summary = "The live topics of one style and level")
    @GetMapping("/pool")
    ResponseEntity<PoolResponse> pool(
            @RequestParam(required = false) @Nullable @Schema(allowableValues = {"prep", "star"}) String style,
            @RequestParam(required = false)
                    @Nullable
                    @Schema(allowableValues = {"starter", "elementary", "intermediate", "upper_intermediate"})
                    String level,
            WebRequest request) {
        Style wantedStyle = style == null ? null : Style.fromWire(style).orElse(null);
        Level wantedLevel = level == null ? null : Level.fromWire(level).orElse(null);
        if (wantedStyle == null || wantedLevel == null) {
            throw new BusinessRefusal(Reason.VALIDATION_FAILED);
        }
        String etag = "\"" + catalog.version() + "\"";
        if (request.checkNotModified(etag)) {
            return ResponseEntity.status(304).eTag(etag).build();
        }
        List<TopicView> topics = catalog.pool(wantedStyle, wantedLevel).stream()
                .map(TopicsController::view)
                .toList();
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache())
                .body(new PoolResponse(catalog.version(), topics));
    }

    private static TopicView view(Topic t) {
        return new TopicView(t.id(), t.text(), t.roleContext(), t.difficulty(), t.thinkS(), t.speakS(), t.seed());
    }
}
