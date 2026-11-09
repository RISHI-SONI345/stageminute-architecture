package app.stageminute.reports.web;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.analysis.internal.M4aFiles;
import app.stageminute.analysis.web.AnalyzeEndpointTest;
import app.stageminute.support.Api;
import app.stageminute.support.FakeClerk;
import app.stageminute.support.MutableClock;
import java.util.UUID;
import org.springframework.core.env.Environment;

/** A signed-in person with AI consent, who can make a scored take through the real endpoints (reports tests). */
public final class TestPerson {

    private final Environment env;
    private final MutableClock clock;
    private final String clerkId = "user_" + UUID.randomUUID().toString().replace("-", "");

    public TestPerson(Environment env, MutableClock clock) {
        this.env = env;
        this.clock = clock;
        Api.Reply me = api().get("/v1/me");
        assertThat(me.status()).isEqualTo(200);
        String version = me.text("consent.policy_version");
        assertThat(api().put("/v1/me/consent", "{\"ai\":true,\"policy_version\":\"" + version + "\"}")
                        .status())
                .isEqualTo(204);
    }

    public Api api() {
        return new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")))
                .with("X-Device-Id", "device-" + clerkId)
                .bearer(FakeClerk.token(clerkId, clock.instant()).sign());
    }

    /** Reserves a session and analyses one 55-second take; returns the 200 answer. */
    public Api.Reply analyzeNewTake() {
        String key = "t_" + UUID.randomUUID().toString().replace("-", "");
        Api.Reply reserved = api().post(
                        "/v1/sessions",
                        "{\"take_key\":\"" + key + "\",\"topic_id\":\"" + AnalyzeEndpointTest.TOPIC + "\"}");
        assertThat(reserved.status()).isEqualTo(201);
        String meta = "{\"session_id\":\"" + reserved.text("session_id") + "\",\"take_key\":\"" + key
                + "\",\"duration_ms\":55000}";
        Api.Reply reply = api().with("Idempotency-Key", key).postAudio("/v1/analyze", M4aFiles.ofLength(55_000), meta);
        assertThat(reply.status()).isEqualTo(200);
        return reply;
    }

    /** A scored take's analysis id. */
    public UUID newReport() {
        return UUID.fromString(analyzeNewTake().text("report.analysis_id"));
    }

    /** A scored take made one day later, so the free session of the new account day is there. */
    public UUID newReportNextDay() {
        clock.advance(java.time.Duration.ofDays(1));
        return newReport();
    }
}
