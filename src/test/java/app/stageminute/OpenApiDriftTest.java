package app.stageminute;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.support.Api;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.FakeClerk;
import app.stageminute.support.MutableClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The OpenAPI file is generated from the controllers and committed as {@code server/openapi.json}; the app's
 * typed client is generated from that file (AD-08, AD-57). This test starts the server, asks it for its API
 * description and fails when the committed file is different, so the app can never be built against an API
 * the server no longer has.
 *
 * <p>After a deliberate API change, regenerate the file with {@code UPDATE_OPENAPI=1 ./gradlew test --tests
 * OpenApiDriftTest}, then run {@code npm run api:gen} in {@code app/}.
 */
@TestPropertySource(properties = "springdoc.api-docs.enabled=true") // off everywhere else (fail closed)
class OpenApiDriftTest extends DatabaseTest {

    private static final Path COMMITTED = Path.of("openapi.json");

    @Autowired
    Environment env;

    @Autowired
    MutableClock clock;

    @Test
    void theCommittedFileIsWhatTheControllersDescribe() throws IOException {
        JsonMapper mapper = JsonMapper.builder()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
        Api api = new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")))
                .bearer(FakeClerk.token("user_openapi", clock.instant()).sign());
        var reply = api.get("/v3/api-docs");
        assertThat(reply.status()).isEqualTo(200);
        JsonNode generated = mapper.readTree(reply.body());
        String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(generated) + "\n";

        if (System.getenv("UPDATE_OPENAPI") != null) {
            Files.writeString(COMMITTED, pretty, StandardCharsets.UTF_8);
        }

        assertThat(COMMITTED)
                .as("server/openapi.json is missing: run with UPDATE_OPENAPI=1 to create it")
                .exists();
        JsonNode committed = mapper.readTree(Files.readString(COMMITTED, StandardCharsets.UTF_8));
        assertThat(generated)
                .as("server/openapi.json is out of date: run with UPDATE_OPENAPI=1, then `npm run api:gen` in app/")
                .isEqualTo(committed);
    }

    /** The wire is snake_case (06-API). A camelCase name in the file means the app's client would not match. */
    @Test
    void everyPropertyInTheFileIsSnakeCase() throws IOException {
        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode committed = mapper.readTree(Files.readString(COMMITTED, StandardCharsets.UTF_8));

        committed
                .path("components")
                .path("schemas")
                .forEach(schema -> assertThat(schema.path("properties").propertyNames())
                        .allMatch(name -> name.matches("[a-z][a-z0-9_]*")));
    }

    @Test
    void theWebhookIsNotInTheAppsApiFile() throws IOException {
        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode committed = mapper.readTree(Files.readString(COMMITTED, StandardCharsets.UTF_8));

        assertThat(committed.path("paths").propertyNames()).noneMatch(path -> path.contains("webhook"));
        assertThat(committed.path("paths").propertyNames()).contains("/v1/me", "/v1/me/consent");
    }
}
