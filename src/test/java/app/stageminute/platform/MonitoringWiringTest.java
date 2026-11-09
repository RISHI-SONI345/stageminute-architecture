package app.stageminute.platform;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.support.Api;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.FakeClerk;
import app.stageminute.support.HttpProbe;
import app.stageminute.support.MutableClock;
import io.micrometer.core.instrument.MeterRegistry;
import io.sentry.Sentry;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/** The running application: Sentry is off without an address, metrics carry no person, ids are on responses. */
class MonitoringWiringTest extends DatabaseTest {

    @Autowired
    Environment env;

    @Autowired
    MeterRegistry registry;

    @Autowired
    MutableClock clock;

    @Test
    void sentryIsOffWhenNoAddressIsGiven() {
        assertThat(env.getProperty("sentry.dsn", "")).isEmpty();
        assertThat(Sentry.isEnabled()).isFalse();
    }

    @Test
    void sentryIsToldNotToSendDefaultPersonalDataOrBodies() {
        assertThat(env.getProperty("sentry.send-default-pii", Boolean.class)).isFalse();
        assertThat(env.getProperty("sentry.max-request-body-size")).isEqualTo("none");
        assertThat(env.getProperty("sentry.max-breadcrumbs", Integer.class)).isZero();
    }

    @Test
    void everyResponseCarriesARequestId() throws Exception {
        int port = Integer.parseInt(env.getRequiredProperty("local.server.port"));
        java.net.http.HttpResponse<String> reply = java.net.http.HttpClient.newHttpClient()
                .send(
                        java.net.http.HttpRequest.newBuilder(
                                        java.net.URI.create("http://localhost:" + port + "/actuator/health/liveness"))
                                .GET()
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(reply.headers().firstValue("X-Request-Id")).isPresent();
        // Past sign-in, an unknown path is a plain 404 that still carries the request id.
        var notFound = new Api(port)
                .bearer(FakeClerk.token("user_probe", clock.instant()).sign())
                .get("/no/such/path");
        assertThat(notFound.status()).isEqualTo(404);
        assertThat(notFound.header("X-Request-Id")).isNotBlank();
        assertThat(HttpProbe.get(port, "/no/such/path").status()).isEqualTo(401);
    }

    @Test
    void aMeterWithAPersonLabelIsNeverCreatedAndNoMeterHasOne() {
        registry.counter("stageminute.test.bad", "user_id", "user_abc12345").increment();

        assertThat(registry.find("stageminute.test.bad").meters()).isEmpty();
        registry.getMeters()
                .forEach(meter -> meter.getId().getTags().forEach(tag -> {
                    String key = tag.getKey().toLowerCase(Locale.ROOT);
                    assertThat(key)
                            .as(meter.getId().getName())
                            .doesNotContain("user")
                            .isNotEqualTo("email");
                    assertThat(tag.getValue()).as(meter.getId().getName()).doesNotContain("@");
                }));
    }
}
