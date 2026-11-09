package app.stageminute.identity.internal;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /v1/webhooks/clerk} (06-API section 2). Clerk calls this when a user is deleted or changed
 * there. The request carries no session token; it proves itself with a Svix signature, checked here before
 * anything else is read. After the check the answer is always {@code 200}: Clerk must not keep retrying
 * because we have no such user (03 F-092).
 *
 * <p>With no {@code CLERK_WEBHOOK_SECRET} the endpoint is off and answers {@code 503} to everybody: until the
 * server has an https address there is nothing for Clerk to call, and an endpoint that cannot check
 * signatures must not accept anything.
 */
@Hidden // called by Clerk, not by the app, so it is not in the app's API file
@RestController
@RequestMapping("/v1/webhooks/clerk")
class ClerkWebhookController {

    private static final Logger LOG = LoggerFactory.getLogger(ClerkWebhookController.class);
    private static final Duration TOLERANCE = Duration.ofMinutes(5);
    /** A real Clerk event is a few kilobytes. Anything bigger is refused before it is read into memory. */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final SvixSignature signature; // null while the webhook is off
    private final Clock clock;
    private final JsonMapper json;
    private final ClerkWebhookHandler handler;

    ClerkWebhookController(ClerkProperties clerk, Clock clock, JsonMapper json, ClerkWebhookHandler handler) {
        this.signature = clerk.webhookSecret().isEmpty() ? null : new SvixSignature(clerk.webhookSecret());
        this.clock = clock;
        this.json = json;
        this.handler = handler;
        if (signature == null) {
            LOG.warn("CLERK_WEBHOOK_SECRET is not set: POST /v1/webhooks/clerk is off");
        }
    }

    @PostMapping
    ResponseEntity<Void> receive(
            HttpServletRequest request,
            @RequestHeader(name = "svix-id", required = false) String messageId,
            @RequestHeader(name = "svix-timestamp", required = false) String timestamp,
            @RequestHeader(name = "svix-signature", required = false) String signatureHeader)
            throws IOException {
        if (signature == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
        }
        if (!signature.verify(messageId, timestamp, body, signatureHeader, clock.instant(), TOLERANCE)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        JsonNode event;
        try {
            event = json.readTree(body);
        } catch (JacksonException e) {
            // Signed but not JSON: nothing to do, and retrying cannot help. Never log the body.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        handler.handle(messageId, event, clock.millis());
        return ResponseEntity.ok().build();
    }
}
