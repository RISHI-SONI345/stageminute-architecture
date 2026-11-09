package app.stageminute.reports.web;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.analysis.internal.M4aFiles;
import app.stageminute.analysis.vendor.FakeScenario;
import app.stageminute.analysis.web.AnalyzeEndpointTest;
import app.stageminute.support.Api;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.FakeClerk;
import app.stageminute.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code GET /v1/analyses} and {@code GET /v1/analyses/{id}} (#15.4; 06-API section 7; 03 F-026, F-057) and what the
 * server keeps of a take: the list of the last 7 days, one report without {@code speech_data}, 404 after 7 days and for
 * someone else's id, nothing kept for a take with no score, and no word of the take in the stored report or its facts
 * except the short quotes (F-119) and the model's own lines. Same test app as {@code AnalyzeEndpointTest}.
 */
@Import(AnalyzeEndpointTest.Doubles.class)
@TestPropertySource(
        properties = {
            "stageminute.analysis.fake-vendors=true",
            "spring.profiles.active=test",
            "logging.level.root=DEBUG"
        })
class AnalysesEndpointTest extends DatabaseTest {

    /**
     * The model's own lines the stored report may keep (F-026): {@code verdict} here, {@code fix_first.try} and {@code
     * strongest_line.why} below. Its rewording of the answer ({@code rewrite}, {@code language_tries}) is not kept
     * (C-71): it goes to the phone once with {@code speech_data}.
     */
    private static final List<String> MODEL_LINES = List.of("verdict");

    /** The two fields that travel with {@code speech_data} and never reach the server's copy of a report (C-71). */
    private static final List<String> PHONE_ONLY = List.of("rewrite", "language_tries");

    @Autowired
    Environment env;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AnalyzeEndpointTest.Scripted stt;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void reset() {
        clock.advance(Duration.between(clock.instant(), Instant.parse("2026-10-29T10:00:00Z")));
        stt.scenario = FakeScenario.OK;
    }

    @Test
    void aScoredTakeIsListedAndOpensWithoutItsWords() {
        Person person = new Person();
        JsonNode analyzed = json.readTree(person.analyzeNewTake().body());
        String id = analyzed.path("report").path("analysis_id").asString();

        Api.Reply list = person.api().get("/v1/analyses?window=7d");
        Api.Reply one = person.api().get("/v1/analyses/" + id);

        assertThat(list.status()).isEqualTo(200);
        assertThat(list.text("items[0].analysis_id")).isEqualTo(id);
        assertThat(list.text("items[0].topic_id")).isEqualTo(AnalyzeEndpointTest.TOPIC);
        assertThat(list.value("items[0].duration_ms")).isEqualTo(55_000);
        assertThat(list.value("items[0].score_total")).isEqualTo(87);
        assertThat(list.text("items[0].status")).isEqualTo("ok");
        assertThat(one.status()).isEqualTo(200);
        assertThat(json.readTree(one.body())).isEqualTo(analyzed.path("report"));
        assertThat(one.body()).doesNotContain("speech_data").doesNotContain("\"transcript\"");
        for (String field : PHONE_ONLY) {
            assertThat(json.readTree(one.body()).has(field)).as(field).isFalse();
        }
    }

    @Test
    void afterSevenDaysAReportCannotBeOpenedOrListed() {
        Person person = new Person();
        String id = json.readTree(person.analyzeNewTake().body())
                .path("report")
                .path("analysis_id")
                .asString();

        clock.advance(Duration.ofDays(7).minusMinutes(1));
        assertThat(person.api().get("/v1/analyses/" + id).status()).isEqualTo(200);

        clock.advance(Duration.ofMinutes(2));
        Api.Reply gone = person.api().get("/v1/analyses/" + id);
        assertThat(gone.status()).isEqualTo(404);
        assertThat(gone.text("reason")).isEqualTo("not_found");
        assertThat((List<?>) person.api().get("/v1/analyses").value("items")).isEmpty();
    }

    @Test
    void someoneElsesReportIsNotFound() {
        Person owner = new Person();
        String id = json.readTree(owner.analyzeNewTake().body())
                .path("report")
                .path("analysis_id")
                .asString();
        Person other = new Person();

        assertThat(other.api().get("/v1/analyses/" + id).status()).isEqualTo(404);
        assertThat((List<?>) other.api().get("/v1/analyses").value("items")).isEmpty();
        assertThat(other.api().get("/v1/analyses/" + UUID.randomUUID()).status())
                .isEqualTo(404);
    }

    @Test
    void aTakeWithNoScoreIsNotKept() {
        Person person = new Person();
        stt.scenario = FakeScenario.NOT_ENGLISH;

        Api.Reply reply = person.analyzeNewTake();

        assertThat(reply.text("report.status")).isEqualTo("not_english");
        assertThat((List<?>) person.api().get("/v1/analyses").value("items")).isEmpty();
        assertThat(person.api()
                        .get("/v1/analyses/" + reply.text("report.analysis_id"))
                        .status())
                .isEqualTo(404);
    }

    @Test
    void onlyTheSevenDayWindowIsOffered() {
        Api.Reply reply = new Person().api().get("/v1/analyses?window=30d");

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.text("reason")).isEqualTo("validation_failed");
    }

    @Test
    void theStoredReportAndFactsHoldNoWordOfTheTakeExceptQuotes() { // 03 F-026 "Done when", F-119
        Person person = new Person();
        JsonNode answer = json.readTree(person.analyzeNewTake().body());
        UUID id = UUID.fromString(answer.path("report").path("analysis_id").asString());
        Set<String> spoken = new HashSet<>();
        answer.path("speech_data").path("words").forEach(word -> {
            String plain = word.path("w").asString().replaceAll("[^\\p{L}]", "").toLowerCase(Locale.ROOT);
            if (plain.length() >= 5) {
                spoken.add(plain);
            }
        });
        assertThat(spoken).contains("checkout", "rebuilt");

        ObjectNode stored = (ObjectNode) json.readTree(jdbc.sql("SELECT report::text FROM reports WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single());
        assertThat(stored.has("speech_data")).isFalse();
        for (String field : PHONE_ONLY) {
            assertThat(stored.has(field)).as("stored " + field + " (C-71)").isFalse();
        }
        stored.remove("quotes"); // the short quotes: the one place the take's words are kept (F-119)
        ((ObjectNode) stored.path("strongest_line")).remove("quote");
        MODEL_LINES.forEach(stored::remove);
        ((ObjectNode) stored.path("fix_first")).remove("try");
        ((ObjectNode) stored.path("strongest_line")).remove("why");
        assertThat(words(stored))
                .as("stored report outside quotes and model lines")
                .doesNotContainAnyElementsOf(spoken);

        String facts = jdbc.sql("SELECT row_to_json(f)::text FROM facts_records f WHERE report_id = :id")
                .param("id", id)
                .query(String.class)
                .single();
        assertThat(words(json.readTree(facts))).as("facts_records").doesNotContainAnyElementsOf(spoken);
    }

    @Test
    void aReportSavedBeforeTheRewordingMovedToThePhoneStillOpensAndV6StripsIt() { // C-71, AD-136
        Person person = new Person();
        UUID id = UUID.fromString(json.readTree(person.analyzeNewTake().body())
                .path("report")
                .path("analysis_id")
                .asString());
        jdbc.sql("UPDATE reports SET report = report || '{\"rewrite\": \"Old words.\", \"language_tries\": {\"0\":"
                        + " \"was\"}}'::jsonb WHERE id = :id")
                .param("id", id)
                .update();

        Api.Reply old = person.api().get("/v1/analyses/" + id);
        assertThat(old.status()).isEqualTo(200);
        assertThat(old.body()).doesNotContain("rewrite").doesNotContain("Old words");

        jdbc.sql(migration("V6__report_rewording_to_phone.sql")).update();
        String stored = jdbc.sql("SELECT report::text FROM reports WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
        assertThat(stored).doesNotContain("rewrite").doesNotContain("language_tries");
    }

    private static String migration(String name) {
        try (var in = AnalysesEndpointTest.class.getResourceAsStream("/db/migration/" + name)) {
            return new String(
                    java.util.Objects.requireNonNull(in).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> words(JsonNode node) {
        Set<String> found = new HashSet<>();
        collect(node, found);
        return found;
    }

    private static void collect(JsonNode node, Set<String> found) {
        if (node.isString()) {
            for (String token : node.asString().split("[^\\p{L}]+", -1)) {
                found.add(token.toLowerCase(Locale.ROOT));
            }
            return;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                found.add(field.getKey().toLowerCase(Locale.ROOT));
                collect(field.getValue(), found);
            }
            return;
        }
        for (JsonNode child : node) {
            collect(child, found);
        }
    }

    /** A signed-in person with AI consent; each take gets its own day so the free session is always there. */
    private final class Person {
        private final String clerkId = "user_" + UUID.randomUUID().toString().replace("-", "");

        Person() {
            Api.Reply me = api().get("/v1/me");
            assertThat(me.status()).isEqualTo(200);
            String version = me.text("consent.policy_version");
            assertThat(api().put("/v1/me/consent", "{\"ai\":true,\"policy_version\":\"" + version + "\"}")
                            .status())
                    .isEqualTo(204);
        }

        Api api() {
            return new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")))
                    .with("X-Device-Id", "device-" + clerkId)
                    .bearer(FakeClerk.token(clerkId, clock.instant()).sign());
        }

        Api.Reply analyzeNewTake() {
            String key = "t_" + UUID.randomUUID().toString().replace("-", "");
            Api.Reply reserved = api().post(
                            "/v1/sessions",
                            "{\"take_key\":\"" + key + "\",\"topic_id\":\"" + AnalyzeEndpointTest.TOPIC + "\"}");
            assertThat(reserved.status()).isEqualTo(201);
            String meta = "{\"session_id\":\"" + reserved.text("session_id") + "\",\"take_key\":\"" + key
                    + "\",\"duration_ms\":55000}";
            Api.Reply reply =
                    api().with("Idempotency-Key", key).postAudio("/v1/analyze", M4aFiles.ofLength(55_000), meta);
            assertThat(reply.status()).isEqualTo(200);
            return reply;
        }
    }
}
