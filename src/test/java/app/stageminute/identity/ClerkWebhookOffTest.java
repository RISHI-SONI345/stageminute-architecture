package app.stageminute.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.support.Api;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.FakeClerk;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

/**
 * Until the server has an https address there is no webhook secret (founder decision, ticket #8). The endpoint
 * is built but off: with no secret it answers 503 to everyone, even to a perfectly signed message (fail closed).
 */
@TestPropertySource(properties = "stageminute.clerk.webhook-secret=")
class ClerkWebhookOffTest extends DatabaseTest {

    @Autowired
    Environment env;

    @Test
    void withoutASecretTheWebhookIsOffForEveryone() {
        String id = "msg_off";
        String body = "{\"type\":\"user.deleted\",\"data\":{\"id\":\"user_x\"}}";
        String timestamp = Long.toString(java.time.Instant.now().getEpochSecond());

        var reply = new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")))
                .with("svix-id", id)
                .with("svix-timestamp", timestamp)
                .with("svix-signature", FakeClerk.svixSignature(id, timestamp, body))
                .post("/v1/webhooks/clerk", body);

        assertThat(reply.status()).isEqualTo(503);
    }
}
