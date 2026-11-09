package app.stageminute.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.support.Api;
import app.stageminute.support.Api.Reply;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.FakeClerk;
import app.stageminute.support.MutableClock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Ticket #8 acceptance: {@code POST /webhooks/clerk} rejects a bad Svix signature and handles
 * {@code user.deleted} once. Also: answer 200 for an unknown user, order-safe by event time, and the
 * inbox keeps the event id and outcome only, never the payload (03 F-092, AD-21).
 */
class ClerkWebhookTest extends DatabaseTest {

    @Autowired
    Environment env;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    private Api api() {
        return new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")))
                .with("X-Device-Id", "device-" + UUID.randomUUID());
    }

    private static String clerkId() {
        return "user_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String messageId() {
        return "msg_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void signUp(String clerk) {
        assertThat(api().bearer(FakeClerk.token(clerk, clock.instant()).sign())
                        .get("/v1/me")
                        .status())
                .isEqualTo(200);
    }

    private int users(String clerk) {
        return jdbc.sql("SELECT count(*) FROM users WHERE clerk_user_id = :c")
                .param("c", clerk)
                .query(Integer.class)
                .single();
    }

    private String outcome(String messageId) {
        return jdbc.sql("SELECT outcome FROM webhook_events WHERE source = 'clerk' AND event_id = :e")
                .param("e", messageId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private Reply deliver(String messageId, String body) {
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        return api().with("svix-id", messageId)
                .with("svix-timestamp", timestamp)
                .with("svix-signature", FakeClerk.svixSignature(messageId, timestamp, body))
                .post("/v1/webhooks/clerk", body);
    }

    private String event(String type, String clerk, String extra, long millis) {
        return "{\"type\":\"" + type + "\",\"object\":\"event\",\"timestamp\":" + millis + ",\"data\":{\"id\":\""
                + clerk + "\"" + extra + "}}";
    }

    @Test
    void userDeletedRemovesTheAccountAndNotesTheDeletion() {
        String clerk = clerkId();
        signUp(clerk);
        String id = messageId();

        Reply reply = deliver(id, event("user.deleted", clerk, ",\"deleted\":true", clock.millis()));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(users(clerk)).isZero();
        assertThat(outcome(id)).isEqualTo("applied");
        assertThat(jdbc.sql("SELECT count(*) FROM deleted_users WHERE deleted_at >= :t")
                        .param("t", java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC))
                        .query(Integer.class)
                        .single())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void theSameMessageDeliveredAgainIsHandledOnce() {
        String clerk = clerkId();
        signUp(clerk);
        String id = messageId();
        String body = event("user.deleted", clerk, "", clock.millis());
        assertThat(deliver(id, body).status()).isEqualTo(200);
        String fresh = clerkId();
        signUp(fresh); // the same person signs up again; Clerk gives a new user id, and we make a new account

        Reply again = deliver(id, body); // Svix delivers the same message a second time

        assertThat(again.status()).isEqualTo(200);
        assertThat(users(clerk)).isZero();
        assertThat(users(fresh)).isEqualTo(1); // the second delivery did not delete the new account
    }

    @Test
    void anUnknownUserIsAnswered200AndRecordedAsUnknown() {
        String id = messageId();

        Reply reply = deliver(id, event("user.deleted", clerkId(), "", clock.millis()));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(outcome(id)).isEqualTo("ignored_unknown_user");
    }

    @Test
    void aBadSignatureIsRejectedAndChangesNothing() {
        String clerk = clerkId();
        signUp(clerk);
        String id = messageId();
        String body = event("user.deleted", clerk, "", clock.millis());
        String timestamp = Long.toString(clock.instant().getEpochSecond());

        Reply reply = api().with("svix-id", id)
                .with("svix-timestamp", timestamp)
                .with("svix-signature", FakeClerk.svixSignature(id, timestamp, "{\"another\":\"body\"}"))
                .post("/v1/webhooks/clerk", body);

        assertThat(reply.status()).isEqualTo(400);
        assertThat(users(clerk)).isEqualTo(1);
        assertThat(outcome(id)).isNull();
    }

    @Test
    void missingSvixHeadersAreRejected() {
        String clerk = clerkId();
        signUp(clerk);

        Reply reply = api().post("/v1/webhooks/clerk", event("user.deleted", clerk, "", clock.millis()));

        assertThat(reply.status()).isEqualTo(400);
        assertThat(users(clerk)).isEqualTo(1);
    }

    @Test
    void aGenuinelySignedButOldMessageIsRejected() {
        String clerk = clerkId();
        signUp(clerk);
        String id = messageId();
        String body = event("user.deleted", clerk, "", clock.millis());
        String old = Long.toString(clock.instant().getEpochSecond() - 600);

        Reply reply = api().with("svix-id", id)
                .with("svix-timestamp", old)
                .with("svix-signature", FakeClerk.svixSignature(id, old, body))
                .post("/v1/webhooks/clerk", body);

        assertThat(reply.status()).isEqualTo(400);
        assertThat(users(clerk)).isEqualTo(1);
    }

    @Test
    void userUpdatedStoresThePrimaryEmailAndFlagsAnAppleRelayAddress() {
        String clerk = clerkId();
        signUp(clerk);
        String extra = ",\"primary_email_address_id\":\"idn_2\",\"email_addresses\":["
                + "{\"id\":\"idn_1\",\"email_address\":\"old@example.com\"},"
                + "{\"id\":\"idn_2\",\"email_address\":\"abc123@privaterelay.appleid.com\"}]";
        String id = messageId();

        assertThat(deliver(id, event("user.updated", clerk, extra, clock.millis()))
                        .status())
                .isEqualTo(200);

        var row = jdbc.sql("SELECT email, email_is_relay FROM users WHERE clerk_user_id = :c")
                .param("c", clerk)
                .query()
                .singleRow();
        assertThat(row)
                .containsEntry("email", "abc123@privaterelay.appleid.com")
                .containsEntry("email_is_relay", true);
        assertThat(outcome(id)).isEqualTo("applied");
    }

    @Test
    void userUpdatedRemembersTheGoogleOrAppleIdBehindTheClerkAccount() {
        String clerk = clerkId();
        signUp(clerk);
        String google =
                ",\"external_accounts\":[{\"id\":\"idn_x\",\"provider\":\"oauth_google\",\"provider_user_id\":\"1138\"}]";

        assertThat(deliver(messageId(), event("user.updated", clerk, google, clock.millis()))
                        .status())
                .isEqualTo(200);

        assertThat(link(clerk)).containsEntry("auth_provider", "google").containsEntry("auth_subject", "1138");
        String apple = ",\"external_accounts\":[{\"provider\":\"apple\",\"provider_user_id\":\"001.abc\"}]";
        deliver(messageId(), event("user.updated", clerk, apple, clock.millis() + 1));
        assertThat(link(clerk)).containsEntry("auth_provider", "apple").containsEntry("auth_subject", "001.abc");
    }

    @Test
    void anUpdateWithoutALinkedAccountOrWithAnUnknownProviderKeepsWhatWeHave() {
        String clerk = clerkId();
        signUp(clerk);
        String google = ",\"external_accounts\":[{\"provider\":\"google\",\"provider_user_id\":\"77\"}]";
        deliver(messageId(), event("user.updated", clerk, google, clock.millis()));

        deliver(messageId(), event("user.updated", clerk, ",\"external_accounts\":[]", clock.millis() + 1));
        String other = ",\"external_accounts\":[{\"provider\":\"oauth_github\",\"provider_user_id\":\"9\"}]";
        deliver(messageId(), event("user.updated", clerk, other, clock.millis() + 2));
        String empty = ",\"external_accounts\":[{\"provider\":\"google\",\"provider_user_id\":\"\"}]";
        deliver(messageId(), event("user.updated", clerk, empty, clock.millis() + 3));

        assertThat(link(clerk)).containsEntry("auth_provider", "google").containsEntry("auth_subject", "77");
    }

    private java.util.Map<String, Object> link(String clerk) {
        return jdbc.sql("SELECT auth_provider, auth_subject FROM users WHERE clerk_user_id = :c")
                .param("c", clerk)
                .query()
                .singleRow();
    }

    @Test
    void anOlderUserUpdatedNeverOverwritesANewerOne() {
        String clerk = clerkId();
        signUp(clerk);
        long newer = clock.millis();
        long older = newer - 60_000;
        String emailOf =
                ",\"primary_email_address_id\":\"i\",\"email_addresses\":[{\"id\":\"i\",\"email_address\":\"%s\"}]";
        assertThat(deliver(messageId(), event("user.updated", clerk, emailOf.formatted("new@example.com"), newer))
                        .status())
                .isEqualTo(200);
        String lateId = messageId();

        Reply late = deliver(lateId, event("user.updated", clerk, emailOf.formatted("stale@example.com"), older));

        assertThat(late.status()).isEqualTo(200);
        assertThat(outcome(lateId)).isEqualTo("ignored_older");
        assertThat(jdbc.sql("SELECT email FROM users WHERE clerk_user_id = :c")
                        .param("c", clerk)
                        .query(String.class)
                        .single())
                .isEqualTo("new@example.com");
    }

    @Test
    void anEventWeDoNotHandleIsAnswered200() {
        assertThat(deliver(messageId(), event("session.created", clerkId(), "", clock.millis()))
                        .status())
                .isEqualTo(200);
    }

    @Test
    void theInboxKeepsNoPayload() {
        Integer payloadColumns = jdbc.sql("SELECT count(*) FROM information_schema.columns"
                        + " WHERE table_name = 'webhook_events' AND column_name IN ('payload','body','data')")
                .query(Integer.class)
                .single();

        assertThat(payloadColumns).isZero();
    }
}
