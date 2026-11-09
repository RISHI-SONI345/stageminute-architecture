package app.stageminute;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.support.DatabaseTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/** The server starts (with its database) and answers /actuator/health (03 F-089 `/health`). */
class StageMinuteApplicationTests extends DatabaseTest {

    @Autowired
    Environment env;

    @Test
    void healthIsUp() throws Exception {
        String port = env.getRequiredProperty("local.server.port");
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    /** Fail closed: with no sign-in yet, only health and info are public. The module map is for the local profile only. */
    @Test
    void moduleMapIsNotPublicByDefault() throws Exception {
        String port = env.getRequiredProperty("local.server.port");
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/modulith"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        // 404 when the endpoint is not exposed; 401 for a request with no sign-in. Either way it is closed.
        assertThat(response.statusCode()).isIn(401, 404);
    }
}
