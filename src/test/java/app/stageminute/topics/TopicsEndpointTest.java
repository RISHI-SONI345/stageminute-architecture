package app.stageminute.topics;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.support.Api;
import app.stageminute.support.Api.Reply;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.FakeClerk;
import app.stageminute.support.MutableClock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/** {@code GET /v1/topics/pool} over real HTTP (06-API section 4, F-014). */
class TopicsEndpointTest extends DatabaseTest {

    @Autowired
    Environment env;

    @Autowired
    MutableClock clock;

    @Autowired
    TopicCatalog catalog;

    private Api person() {
        String clerk = "user_" + UUID.randomUUID().toString().replace("-", "");
        return new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")))
                .with("X-Device-Id", "device-" + UUID.randomUUID())
                .with("X-Platform", "android")
                .bearer(FakeClerk.token(clerk, clock.instant()).sign());
    }

    @Test
    void thePoolHoldsTheLiveTopicsOfTheStyleAndLevelWithTheCatalogVersion() {
        Reply reply = person().get("/v1/topics/pool?style=prep&level=intermediate");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.text("$.version")).isEqualTo(catalog.version());
        List<String> expected = catalog.pool(Style.PREP, Level.INTERMEDIATE).stream()
                .map(Topic::id)
                .toList();
        assertThat((List<?>) reply.value("$.topics[*].id")).isEqualTo(expected).hasSize(5);
        assertThat(reply.value("$.topics[0]")).asString().contains("think_s", "speak_s", "difficulty", "role_context");
        assertThat(reply.value("$.topics[0].role_context")).isNull();
        assertThat(reply.value("$.topics[0].think_s")).isEqualTo(30);
        assertThat(reply.value("$.topics[0].speak_s")).isEqualTo(60);
    }

    @Test
    void aStarTopicCarriesWhoAsksAndTheLongerSpeakTime() {
        Reply reply = person().get("/v1/topics/pool?style=star&level=starter");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.text("$.topics[0].role_context")).endsWith("asks:");
        assertThat(reply.value("$.topics[0].speak_s")).isEqualTo(90);
    }

    @Test
    void thePoolMarksTheSeedSoThePhoneCanTakeItOnTheFirstSpinAndNeverListsRetired() {
        Reply reply = person().get("/v1/topics/pool?style=prep&level=starter");

        assertThat((List<?>) reply.value("$.topics[?(@.seed == true)].id")).hasSize(1);
        assertThat((List<?>) reply.value("$.topics[?(@.seed == false)].id")).hasSize(4);
        assertThat(reply.body()).doesNotContain("retired");
    }

    @Test
    void theEtagIsTheCatalogVersionAndTheSameForTheSameCatalog() {
        Reply first = person().get("/v1/topics/pool?style=prep&level=starter");
        Reply again = person().get("/v1/topics/pool?style=prep&level=starter");

        assertThat(first.header("ETag")).isEqualTo("\"" + catalog.version() + "\"");
        assertThat(again.header("ETag")).isEqualTo(first.header("ETag"));
    }

    @Test
    void ifNoneMatchWithTheCurrentVersionAnswers304WithNoBody() {
        Api person = person();
        String etag = person.get("/v1/topics/pool?style=prep&level=starter").header("ETag");

        Reply notModified = person.with("If-None-Match", etag).get("/v1/topics/pool?style=prep&level=starter");

        assertThat(notModified.status()).isEqualTo(304);
        assertThat(notModified.body()).isEmpty();
        assertThat(notModified.header("ETag")).isEqualTo(etag);
    }

    @Test
    void anOldVersionGetsTheFullPoolAgain() {
        Reply reply =
                person().with("If-None-Match", "\"000000000000\"").get("/v1/topics/pool?style=prep&level=starter");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.text("$.version")).isEqualTo(catalog.version());
    }

    @Test
    void aMissingOrUnknownStyleOrLevelIsRefusedAsValidationFailed() {
        for (String query :
                List.of("", "?style=prep", "?level=starter", "?style=poem&level=starter", "?style=prep&level=expert")) {
            Reply reply = person().get("/v1/topics/pool" + query);

            assertThat(reply.status()).as(query).isEqualTo(400);
            assertThat(reply.text("$.reason")).as(query).isEqualTo("validation_failed");
        }
    }

    @Test
    void thePoolNeedsASignedInUser() {
        Reply reply = new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")))
                .get("/v1/topics/pool?style=prep&level=starter");

        assertThat(reply.status()).isEqualTo(401);
    }

    @Test
    void theCommittedApiFileUsesTheSameNamesAsTheWire() throws java.io.IOException {
        tools.jackson.databind.JsonNode topic = tools.jackson.databind.json.JsonMapper.builder()
                .build()
                .readTree(java.nio.file.Files.readString(java.nio.file.Path.of("openapi.json")))
                .at("/components/schemas/TopicView");

        assertThat(topic.get("properties").propertyNames()).contains("role_context", "think_s", "speak_s");
        assertThat(topic.get("required").valueStream().map(tools.jackson.databind.JsonNode::asString))
                .doesNotContain("role_context");
    }
}
