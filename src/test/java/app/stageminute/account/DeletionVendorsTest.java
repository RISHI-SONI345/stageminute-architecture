package app.stageminute.account;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.platform.EventPublicationLog;
import app.stageminute.platform.EventRetryJob;
import app.stageminute.support.Api;
import app.stageminute.support.Api.Reply;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.FakeClerk;
import app.stageminute.support.MutableClock;
import app.stageminute.support.RecordingVendors;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What happens at the outside services after an account is deleted (05 F-082, 03 F-094, AD-87, AD-149): each vendor is
 * told once with the right id, a vendor that fails is tried again by the retry job without disturbing the others, a
 * job that does not finish is found by the overdue query, and webhooks for the deleted person change nothing.
 */
@Import(RecordingVendors.class)
class DeletionVendorsTest extends DatabaseTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    Environment env;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    RecordingVendors.Clerk clerkVendor;

    @Autowired
    RecordingVendors.RevenueCat revenueCatVendor;

    @Autowired
    RecordingVendors.PostHog postHogVendor;

    @Autowired
    EventRetryJob retryJob;

    @Autowired
    EventPublicationLog eventLog;

    @Autowired
    MeterRegistry meters;

    @Autowired
    Deletion deletion;

    @BeforeEach
    void resetFakes() {
        clerkVendor.reset();
        revenueCatVendor.reset();
        postHogVendor.reset();
        waitUntil(() -> incomplete() == 0, "earlier events to finish");
    }

    private static String clerkId() {
        return "user_" + UUID.randomUUID().toString().replace("-", "");
    }

    private Api api() {
        return new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")))
                .with("X-Device-Id", "device-" + UUID.randomUUID())
                .with("X-Platform", "android");
    }

    private Api as(String clerk) {
        return api().bearer(FakeClerk.token(clerk, clock.instant()).sign());
    }

    private UUID signUp(String clerk) {
        assertThat(as(clerk).get("/v1/me").status()).isEqualTo(200);
        return jdbc.sql("SELECT id FROM users WHERE clerk_user_id = :c")
                .param("c", clerk)
                .query(UUID.class)
                .single();
    }

    private static void waitUntil(BooleanSupplier condition, String what) {
        long end = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + what, e);
            }
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    private int incomplete() {
        return jdbc.sql("SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Integer.class)
                .single();
    }

    /**
     * Listeners that threw and were marked FAILED. An event is "incomplete" from the moment it is published, but the
     * retry job hands back only the ones already marked FAILED; under load the mark comes a little after the vendor
     * call, so a test must wait for it before it runs the retry job (issue #88).
     */
    private int failed() {
        return jdbc.sql("SELECT count(*) FROM event_publication WHERE completion_date IS NULL AND status = 'FAILED'")
                .query(Integer.class)
                .single();
    }

    private Reply deliver(String type, String clerk) {
        String messageId = "msg_" + UUID.randomUUID().toString().replace("-", "");
        String body = "{\"type\":\"" + type + "\",\"object\":\"event\",\"timestamp\":" + clock.millis()
                + ",\"data\":{\"id\":\"" + clerk + "\",\"deleted\":true}}";
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        return api().with("svix-id", messageId)
                .with("svix-timestamp", timestamp)
                .with("svix-signature", FakeClerk.svixSignature(messageId, timestamp, body))
                .post("/v1/webhooks/clerk", body);
    }

    @Test
    void eachVendorIsToldOnceWithTheRightIdAndTheEventsComplete() {
        String clerk = clerkId();
        UUID id = signUp(clerk);

        assertThat(as(clerk).delete("/v1/me").status()).isEqualTo(204);

        waitUntil(() -> clerkVendor.calls().size() == 1, "the Clerk delete");
        waitUntil(() -> revenueCatVendor.calls().size() == 1, "the RevenueCat delete");
        waitUntil(() -> postHogVendor.calls().size() == 1, "the PostHog delete");
        assertThat(clerkVendor.calls()).containsExactly(clerk);
        assertThat(revenueCatVendor.calls()).containsExactly(id.toString());
        assertThat(postHogVendor.calls()).containsExactly(id.toString());
        waitUntil(() -> incomplete() == 0, "every listener to finish");
    }

    @Test
    void aVendorThatFailsIsTriedAgainByTheRetryJobAndTheOthersAreNotRepeated() {
        String clerk = clerkId();
        signUp(clerk);
        clerkVendor.failNext(2);

        assertThat(as(clerk).delete("/v1/me").status()).isEqualTo(204);

        waitUntil(() -> clerkVendor.calls().size() == 1, "the first Clerk try");
        waitUntil(
                () -> revenueCatVendor.calls().size() == 1
                        && postHogVendor.calls().size() == 1,
                "the others");
        waitUntil(() -> incomplete() == 1 && failed() == 1, "only the Clerk listener to stay open, marked failed");

        retryJob.run(); // second try: fails again
        waitUntil(() -> clerkVendor.calls().size() == 2, "the second Clerk try");
        waitUntil(
                () -> incomplete() == 1 && failed() == 1,
                "the Clerk listener to stay open and be marked failed after the second failure");
        retryJob.run(); // third try: goes through
        waitUntil(() -> clerkVendor.calls().size() == 3, "the third Clerk try");
        waitUntil(() -> incomplete() == 0, "the Clerk listener to finish");

        assertThat(revenueCatVendor.calls())
                .as("a finished vendor is not asked again")
                .hasSize(1);
        assertThat(postHogVendor.calls()).hasSize(1);
    }

    @Test
    void aDeletionThatDoesNotFinishIsFoundOnceItIsOlderThanTheAlertTime() {
        String clerk = clerkId();
        signUp(clerk);
        clerkVendor.failNext(1);
        assertThat(as(clerk).delete("/v1/me").status()).isEqualTo(204);
        waitUntil(() -> incomplete() == 1 && failed() == 1, "the Clerk listener to fail and be marked failed");

        assertThat(eventLog.overdueDeletions()).as("not overdue yet").isEmpty();
        clock.advance(Duration.ofHours(6).plusMinutes(1));
        var overdue = eventLog.overdueDeletions();

        assertThat(overdue).hasSize(1);
        assertThat(overdue.get(0).listenerId()).contains("ClerkDeletionListener");
        assertThat(overdue.get(0).isDeletion()).isTrue();

        retryJob.run();
        waitUntil(() -> incomplete() == 0, "the retry to finish the Clerk delete");
        assertThat(eventLog.overdueDeletions()).isEmpty();
    }

    @Test
    void clerkSendingUserDeletedAfterOurOwnDeleteIsAnsweredOkAndChangesNothing() {
        String clerk = clerkId();
        signUp(clerk);
        assertThat(as(clerk).delete("/v1/me").status()).isEqualTo(204);
        int notes = jdbc.sql("SELECT count(*) FROM deleted_users")
                .query(Integer.class)
                .single();

        Reply echo = deliver("user.deleted", clerk);

        assertThat(echo.status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM deleted_users")
                        .query(Integer.class)
                        .single())
                .isEqualTo(notes);
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE clerk_user_id = :c")
                        .param("c", clerk)
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void anUpdateOrSessionEventForADeletedClerkUserIsAnsweredOkAndMakesNoAccount() {
        String clerk = clerkId();
        signUp(clerk);
        assertThat(as(clerk).delete("/v1/me").status()).isEqualTo(204);

        Reply updated = deliver("user.updated", clerk);
        Reply session = deliver("session.created", clerk);

        assertThat(updated.status()).isEqualTo(200);
        assertThat(session.status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE clerk_user_id = :c")
                        .param("c", clerk)
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void deletingAtClerkFirstRunsTheSameDeletionForTheOtherVendors() {
        String clerk = clerkId();
        UUID id = signUp(clerk);

        Reply reply = deliver("user.deleted", clerk); // the person deleted themselves at Clerk

        assertThat(reply.status()).isEqualTo(200);
        waitUntil(
                () -> revenueCatVendor.calls().size() == 1
                        && postHogVendor.calls().size() == 1,
                "the vendors");
        assertThat(revenueCatVendor.calls()).containsExactly(id.toString());
        waitUntil(() -> clerkVendor.calls().size() == 1, "Clerk to be asked (it answers: already gone)");
    }

    @Test
    void superwallHasNoDeleteCallSoTheEventRecordsAManualStepAndIsDone() {
        double before = manualSuperwallSteps();
        String clerk = clerkId();
        signUp(clerk);

        assertThat(as(clerk).delete("/v1/me").status()).isEqualTo(204);

        waitUntil(() -> manualSuperwallSteps() == before + 1, "the Superwall manual-step record");
        waitUntil(() -> incomplete() == 0, "every listener to finish");
    }

    private double manualSuperwallSteps() {
        var counter = meters.find("deletion.vendor")
                .tag("vendor", "superwall")
                .tag("outcome", "not_configured")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void wasDeletedAnswersForTheWebhookTicketsToIgnoreEventsOfADeletedPerson() {
        String clerk = clerkId();
        UUID id = signUp(clerk);
        assertThat(deletion.wasDeleted(id)).isFalse();

        assertThat(as(clerk).delete("/v1/me").status()).isEqualTo(204);

        assertThat(deletion.wasDeleted(id)).isTrue();
        assertThat(deletion.wasDeleted(UUID.randomUUID())).isFalse();
    }
}
