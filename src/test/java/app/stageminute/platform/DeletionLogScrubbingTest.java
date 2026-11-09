package app.stageminute.platform;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.support.Api;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.FakeClerk;
import app.stageminute.support.MutableClock;
import app.stageminute.support.RecordingVendors;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * F-081, 05 F-082: the deletion path, including a vendor that fails and is tried again, logs event names, vendor names
 * and counts only. The person's email, their Clerk id, their token and the vendor's key never reach a log line, even
 * with the loudest log level on; and the events table holds ids only (no email, no name).
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(RecordingVendors.class)
@TestPropertySource(properties = "logging.level.root=DEBUG")
class DeletionLogScrubbingTest extends DatabaseTest {

    private static final String EMAIL = "secret.person.4471@example.com";
    private static final String NAME = "Secretname Personfamily";

    @Autowired
    Environment env;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RecordingVendors.Clerk clerkVendor;

    @Autowired
    EventRetryJob retryJob;

    private Api api() {
        return new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")))
                .with("X-Device-Id", "device-" + UUID.randomUUID())
                .with("X-Platform", "android");
    }

    @Test
    void noEmailClerkIdTokenOrNameReachesALogLineOrTheEventTable(CapturedOutput output) throws Exception {
        String clerk = "user_scrub" + UUID.randomUUID().toString().replace("-", "");
        String token = FakeClerk.token(clerk, clock.instant()).sign();
        Api person = api().bearer(token);
        assertThat(person.get("/v1/me").status()).isEqualTo(200);
        String messageId = "msg_" + UUID.randomUUID().toString().replace("-", "");
        String body = "{\"type\":\"user.updated\",\"object\":\"event\",\"timestamp\":" + clock.millis()
                + ",\"data\":{\"id\":\"" + clerk + "\",\"first_name\":\"" + NAME
                + "\",\"primary_email_address_id\":\"idn_1\",\"email_addresses\":[{\"id\":\"idn_1\","
                + "\"email_address\":\"" + EMAIL + "\"}]}}";
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        assertThat(api().with("svix-id", messageId)
                        .with("svix-timestamp", timestamp)
                        .with("svix-signature", FakeClerk.svixSignature(messageId, timestamp, body))
                        .post("/v1/webhooks/clerk", body)
                        .status())
                .isEqualTo(200);
        assertThat(jdbc.sql("SELECT email FROM users WHERE clerk_user_id = :c")
                        .param("c", clerk)
                        .query(String.class)
                        .single())
                .isEqualTo(EMAIL);
        clerkVendor.reset();
        clerkVendor.failNext(1); // one failed try and one retry both go through the logs

        assertThat(person.delete("/v1/me").status()).isEqualTo(204);
        long end = System.nanoTime() + 15_000_000_000L;
        while (clerkVendor.calls().isEmpty() && System.nanoTime() < end) {
            Thread.sleep(50);
        }
        Thread.sleep(300); // let the failed try finish writing its log lines
        retryJob.run();
        end = System.nanoTime() + 15_000_000_000L;
        while (clerkVendor.calls().size() < 2 && System.nanoTime() < end) {
            Thread.sleep(50);
        }
        Thread.sleep(300);

        String logs = output.getAll();
        assertThat(logs).contains("deletion.vendor");
        assertThat(logs)
                .doesNotContain(EMAIL)
                .doesNotContain(NAME)
                .doesNotContain(clerk)
                .doesNotContain(token);
        String events = jdbc.sql("SELECT string_agg(serialized_event, ' ') FROM event_publication"
                        + " WHERE event_type LIKE '%Deletion%Requested'")
                .query(String.class)
                .single();
        assertThat(events).doesNotContain(EMAIL).doesNotContain(NAME).doesNotContain("@");
    }
}
