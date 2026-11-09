package app.stageminute.platform;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.support.DatabaseTest;
import app.stageminute.support.HttpProbe;
import app.stageminute.support.HttpProbe.Reply;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/** Gap G3 (11-PATTERNS-AUDIT): "alive" and "ready" are different questions. */
class HealthEndpointsTest extends DatabaseTest {

    @Autowired
    Environment env;

    private Reply get(String path) throws Exception {
        return HttpProbe.get(Integer.parseInt(env.getRequiredProperty("local.server.port")), path);
    }

    @Test
    void livenessIsUpWithoutAskingTheDatabase() throws Exception {
        Reply reply = get("/actuator/health/liveness");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("\"status\":\"UP\"");
        assertThat(env.getRequiredProperty("management.endpoint.health.group.liveness.include"))
                .isEqualTo("livenessState");
    }

    @Test
    void readinessIsUpWhenTheDatabaseAndSettingsAreReachable() throws Exception {
        Reply reply = get("/actuator/health/readiness");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void readinessIsDefinedAsStateAndDatabaseAndSettings() {
        assertThat(env.getRequiredProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,db,settingsLoaded");
    }

    @Test
    void healthShowsNoDetailsToTheOutside() throws Exception {
        String body = get("/actuator/health").body();

        assertThat(body).doesNotContain("jdbc").doesNotContain("PostgreSQL").doesNotContain("components");
    }
}
