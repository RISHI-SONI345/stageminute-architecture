package app.stageminute.analysis.web;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.analysis.internal.M4aFiles;
import app.stageminute.analysis.internal.SpeechDataCache;
import app.stageminute.analysis.vendor.FakeScenario;
import app.stageminute.analysis.vendor.FakeSpeechToText;
import app.stageminute.analysis.vendor.SpeechResult;
import app.stageminute.analysis.vendor.SpeechToText;
import app.stageminute.analysis.vendor.SttRequest;
import app.stageminute.platform.UserId;
import app.stageminute.practice.Practice;
import app.stageminute.support.Api;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.FakeClerk;
import app.stageminute.support.MutableClock;
import app.stageminute.topics.Level;
import app.stageminute.topics.Style;
import app.stageminute.topics.Topic;
import app.stageminute.topics.TopicCatalog;
import app.stageminute.topics.TopicSettings;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /v1/analyze} over real HTTP on the fake vendors (#15.3; 06-API section 6; 03 F-026, F-048, F-091,
 * F-122; C-52, C-60, C-69): the stored answer within the retry window, the session given back on a failure, a released
 * session refused with no vendor call, the server's own length, the 60 s cap plus the margin, the loud 413 and 415,
 * consent, a take with no score, attempts 2 to 4, and no word, audio byte or prompt text in a log line or error body.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(AnalyzeEndpointTest.Doubles.class)
@TestPropertySource(
        properties = {
            "stageminute.analysis.fake-vendors=true",
            "spring.profiles.active=test",
            "logging.level.root=DEBUG"
        })
public class AnalyzeEndpointTest extends DatabaseTest {

    public static final String TOPIC = "prep-ele-001";
    private static final String PROMPT_TEXT = "PROMPT-TEXT-55aa01 What made your week?";
    private static final String AUDIO_MARK = "AUDIO-BYTES-9f3c2a";
    private static final List<String> SPOKEN = List.of("checkout", "rebuilt", "customers", "noticed");

    /** The test's own topic (60 s to speak, a prompt marker) and a speech to text whose scenario a test can set. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Doubles {
        @Bean
        @Primary
        TopicCatalog analyzeCatalog() {
            Topic topic = new Topic(TOPIC, Style.PREP, Level.ELEMENTARY, 1, PROMPT_TEXT, null, 30, 60, true, false);
            return TopicCatalog.of(List.of(topic), TopicSettings.LAUNCH_DEFAULT);
        }

        @Bean
        @Primary
        Scripted scriptedSpeechToText() {
            return new Scripted();
        }
    }

    /** The fake speech to text, with a scenario the test sets and a count of calls. */
    public static final class Scripted implements SpeechToText {
        public volatile FakeScenario scenario = FakeScenario.OK;
        public final AtomicInteger calls = new AtomicInteger();

        @Override
        public SpeechResult transcribe(byte[] audio, int durationMs, SttRequest request) {
            calls.incrementAndGet();
            return new FakeSpeechToText(scenario, 0).transcribe(audio, durationMs, request);
        }
    }

    @Autowired
    Environment env;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Scripted stt;

    @Autowired
    SpeechDataCache speechCache;

    @Autowired
    Practice practice;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void reset() {
        clock.advance(Duration.between(clock.instant(), Instant.parse("2026-10-29T10:00:00Z")));
        stt.scenario = FakeScenario.OK;
        stt.calls.set(0);
    }

    @Test
    void anOkTakeGetsItsReportAndWordsAndCommitsTheSession() {
        Person person = newPerson(true);
        Take take = person.reserve();

        Api.Reply reply = person.analyze(take, M4aFiles.ofLength(55_000), 55_000);

        assertThat(reply.status()).isEqualTo(200);
        JsonNode body = json.readTree(reply.body());
        assertThat(body.path("report").path("status").asString()).isEqualTo("ok");
        assertThat(body.path("report").path("score").path("total").asInt()).isEqualTo(87);
        assertThat(body.path("report").path("prompt_version").asString()).isEqualTo("fake-prompt-0");
        assertThat(body.path("speech_data").path("words").size()).isEqualTo(128);
        assertThat(state(take.session())).isEqualTo("committed");
        assertThat(count("SELECT count(*) FROM reports WHERE session_id = :s", take.session()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM facts_records WHERE session_id = :s", take.session()))
                .isEqualTo(1);
    }

    @Test
    void theSameTakeSentAgainWithinTenMinutesGetsTheStoredReport() {
        Person person = newPerson(true);
        Take take = person.reserve();
        Api.Reply first = person.analyze(take, M4aFiles.ofLength(55_000), 55_000);
        clock.advance(Duration.ofMinutes(9));

        Api.Reply again = person.analyze(take, M4aFiles.ofLength(55_000), 55_000);

        assertThat(again.status()).isEqualTo(200);
        assertThat(json.readTree(again.body()).path("report"))
                .isEqualTo(json.readTree(first.body()).path("report"));
        assertThat(json.readTree(again.body()).path("speech_data").path("words").size())
                .isEqualTo(128);
        JsonNode firstWords = json.readTree(first.body()).path("speech_data");
        JsonNode againWords = json.readTree(again.body()).path("speech_data");
        assertThat(firstWords.path("rewrite").asString()).contains("checkout"); // C-71: with the words, once
        assertThat(firstWords.path("language_tries").size()).isPositive();
        assertThat(againWords.path("rewrite")).isEqualTo(firstWords.path("rewrite"));
        assertThat(againWords.path("language_tries")).isEqualTo(firstWords.path("language_tries"));
        assertThat(stt.calls.get()).as("the vendors ran once").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reports WHERE session_id = :s", take.session()))
                .isEqualTo(1);
    }

    @Test
    void afterARestartTheStoredReportComesBackWithoutTheWords() {
        Person person = newPerson(true);
        Take take = person.reserve();
        Api.Reply first = person.analyze(take, M4aFiles.ofLength(55_000), 55_000);
        speechCache.clear(); // what a restart does to the in-memory copy (F-091)

        Api.Reply again = person.analyze(take, M4aFiles.ofLength(55_000), 55_000);

        assertThat(json.readTree(again.body()).path("report"))
                .isEqualTo(json.readTree(first.body()).path("report"));
        assertThat(json.readTree(again.body()).path("speech_data").isNull()).isTrue();
        JsonNode stored = json.readTree(jdbc.sql("SELECT response::text FROM idempotency_entries WHERE key = :k")
                .param("k", "analyze:" + take.key())
                .query(String.class)
                .single());
        assertThat(stored.path("speech_data").isNull())
                .as("the stored answer never holds speech_data")
                .isTrue();
        assertThat(stored.path("report").path("quotes").size()).isPositive(); // the report itself is all there
        assertThat(stored.path("report").has("rewrite")).isFalse(); // C-71
        assertThat(stored.path("report").has("language_tries")).isFalse();
    }

    @Test
    void aFailedAnalysisGivesTheSessionBack() {
        Person person = newPerson(true);
        Take take = person.reserve();
        assertThat(person.sessionsLeft()).isZero();
        stt.scenario = FakeScenario.VENDOR_FAILURE;

        Api.Reply reply = person.analyze(take, M4aFiles.ofLength(55_000), 55_000);

        assertThat(reply.status()).isEqualTo(502);
        assertThat(reply.text("reason")).isEqualTo("analysis_unavailable");
        assertThat(reply.value("session_given_back")).isEqualTo(true);
        assertThat(person.sessionsLeft()).isEqualTo(1);
        assertThat(state(take.session())).isEqualTo("released");
        assertThat(stt.calls.get()).as("no retry by the server (F-122)").isEqualTo(1);
    }

    @Test
    void aReleasedSessionIsRefusedAndNoVendorIsCalled() { // C-52
        Person person = newPerson(true);
        Take take = person.reserve();
        assertThat(person.api()
                        .post("/v1/sessions/" + take.session() + "/release", "{\"reason\":\"discard\"}")
                        .status())
                .isEqualTo(204);

        Api.Reply reply = person.analyze(take, M4aFiles.ofLength(55_000), 55_000);

        assertThat(reply.status()).isEqualTo(409);
        assertThat(reply.text("reason")).isEqualTo("attempt_not_allowed");
        assertThat(reply.text("next_attempt.reason")).isEqualTo("session_ended");
        assertThat(stt.calls.get()).isZero();
        assertThat(state(take.session())).isEqualTo("released");
    }

    @Test
    void aReleaseSentWhileTheTakeIsAnalysedChangesNothing() { // C-52
        Person person = newPerson(true);
        Take take = person.reserve();
        UserId user = person.id();
        practice.beginAnalysis(user, take.session());

        boolean released = practice.release(user, take.session(), Practice.ReleaseReason.DISCARD);

        assertThat(released).isFalse();
        assertThat(state(take.session())).isEqualTo("reserved");
    }

    @Test
    void thePhonesLengthIsOnlyAHint() { // C-69
        Person person = newPerson(true);
        Take shortOnThePhone = person.reserve();
        Api.Reply reply = person.analyze(shortOnThePhone, M4aFiles.ofLength(55_000), 10_000);
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.value("report.facts.duration_ms")).isEqualTo(55_000);

        Person other = newPerson(true);
        Take longOnThePhone = other.reserve();
        Api.Reply tooShort = other.analyze(longOnThePhone, M4aFiles.ofLength(15_000), 55_000);
        assertThat(tooShort.status()).isEqualTo(422);
        assertThat(tooShort.text("reason")).isEqualTo("too_short");
        assertThat(stt.calls.get()).as("only the first take reached a vendor").isEqualTo(1);
    }

    @Test
    void theServerDecidesTheCapAndAcceptsTheMargin() { // C-60
        Person person = newPerson(true);
        Take atTheLimit = person.reserve();
        Api.Reply reply = person.analyze(atTheLimit, M4aFiles.ofLength(60_700), 59_000, false);
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.value("report.facts.hit_cap")).isEqualTo(true);

        Person other = newPerson(true);
        Take pastTheMargin = other.reserve();
        Api.Reply refused = other.analyze(pastTheMargin, M4aFiles.ofLength(63_100), 63_100);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.text("reason")).isEqualTo("validation_failed");
    }

    @Test
    void theAudioIsHeldInMemoryAndNeverWrittenToATempFile(
            @Autowired org.springframework.boot.servlet.autoconfigure.MultipartProperties multipart) {
        // rule 4: the audio exists for one request; a part under the threshold is never written to disk
        assertThat(multipart.getFileSizeThreshold().toBytes())
                .isGreaterThan(multipart.getMaxRequestSize().toBytes());
    }

    @Test
    void aFileOverTheLimitIsALoud413() {
        Person person = newPerson(true);
        Take take = person.reserve();
        byte[] big = new byte[8 * 1024 * 1024 + 1024];

        Api.Reply reply = person.analyze(take, big, 55_000);

        assertThat(reply.status()).isEqualTo(413);
        assertThat(reply.text("reason")).isEqualTo("file_too_large");
        assertThat(reply.value("max_bytes")).isEqualTo(8 * 1024 * 1024);
    }

    @Test
    void somethingThatIsNotAnM4aFileIsALoud415() {
        Person person = newPerson(true);
        Take take = person.reserve();

        Api.Reply reply = person.analyze(take, "RIFF....WAVEfmt ".getBytes(StandardCharsets.US_ASCII), 55_000);

        assertThat(reply.status()).isEqualTo(415);
        assertThat(reply.text("reason")).isEqualTo("unsupported_format");
    }

    @Test
    void withoutAiConsentTheTakeIsNotAnalysed() {
        Person person = newPerson(false);
        Take take = person.reserve();

        Api.Reply reply = person.analyze(take, M4aFiles.ofLength(55_000), 55_000);

        assertThat(reply.status()).isEqualTo(403);
        assertThat(reply.text("reason")).isEqualTo("consent_off");
        assertThat(stt.calls.get()).isZero();
    }

    @Test
    void aTakeWithNoScoreUsesNoSessionAndIsNotKept() {
        Person person = newPerson(true);
        Take take = person.reserve();
        stt.scenario = FakeScenario.SHORT_TAKE;

        Api.Reply reply = person.analyze(take, M4aFiles.ofLength(55_000), 55_000);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.text("report.status")).isEqualTo("short_take");
        assertThat(state(take.session())).isEqualTo("released");
        assertThat(person.sessionsLeft()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reports WHERE session_id = :s", take.session()))
                .isZero();
    }

    @Test
    void attemptsTwoAndThreeAreCheckedAgainstTheFirstAndAFourthIsRefused() {
        Person person = newPerson(true);
        Take first = person.reserve();
        person.analyze(first, M4aFiles.ofLength(55_000), 55_000);

        JsonNode second = json.readTree(
                person.analyze(first.next(), M4aFiles.ofLength(55_000), 55_000).body());
        assertThat(second.path("report").path("attempt").asInt()).isEqualTo(2);
        assertThat(second.path("report").path("focus_check").path("fix_id").asString())
                .isEqualTo("strengthen_step");
        assertThat(second.path("report").path("focus_check").path("result").asString())
                .isEqualTo("not_yet");
        assertThat(second.path("report").path("delta").path("direction").asString())
                .isEqualTo("same");
        assertThat(second.path("report").path("next_attempt").path("reason").asString())
                .isEqualTo("last_try");

        JsonNode third = json.readTree(person.analyze(first.next().next(), M4aFiles.ofLength(55_000), 55_000)
                .body());
        assertThat(third.path("report").path("attempt").asInt()).isEqualTo(3);
        assertThat(third.path("report").path("next_attempt").path("allowed").asBoolean())
                .isFalse();

        Api.Reply fourth = person.analyze(first.next().next().next(), M4aFiles.ofLength(55_000), 55_000);
        assertThat(fourth.status()).isEqualTo(409);
        assertThat(fourth.text("reason")).isEqualTo("attempt_not_allowed");
        assertThat(count("SELECT count(*) FROM reports WHERE session_id = :s", first.session()))
                .isEqualTo(3);
    }

    @Test
    void noWordAudioByteOrPromptTextReachesALogLineOrAnErrorBody(CapturedOutput output) {
        Person person = newPerson(true);
        Api.Reply ok = person.analyze(person.reserve(), marked(55_000), 55_000);
        stt.scenario = FakeScenario.VENDOR_FAILURE;
        Api.Reply failed = person.analyze(person.reserve(), marked(55_000), 55_000);
        stt.scenario = FakeScenario.OK;
        Api.Reply tooShort = person.analyze(person.reserve(), marked(15_000), 15_000);

        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body()).contains("checkout"); // the words do go to the phone, once
        for (String word : SPOKEN) {
            assertThat(output.getAll()).as("log").doesNotContainIgnoringCase(word);
            assertThat(failed.body() + tooShort.body()).as("error bodies").doesNotContainIgnoringCase(word);
        }
        assertThat(output.getAll()).doesNotContain(AUDIO_MARK).doesNotContain("PROMPT-TEXT-55aa01");
        assertThat(failed.body() + tooShort.body()).doesNotContain(AUDIO_MARK).doesNotContain("PROMPT-TEXT-55aa01");
        assertThat(output.getAll()).contains("analyze.vendor_unavailable"); // the event is logged, by name
    }

    /** A header-only take with the audio marker inside a {@code free} box, as real files carry padding. */
    private static byte[] marked(int durationMs) {
        byte[] mark = AUDIO_MARK.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(M4aFiles.ofLength(durationMs));
        out.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(8 + mark.length).array());
        out.writeBytes("free".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(mark);
        return out.toByteArray();
    }

    private record Take(String key, UUID session, Person owner) {
        /** The next attempt on the same topic: a new take key, the same session. */
        Take next() {
            return new Take(newKey(), session, owner);
        }
    }

    private final class Person {
        private final String clerkId = "user_" + UUID.randomUUID().toString().replace("-", "");
        private final String device = "device-" + UUID.randomUUID();

        Api api() {
            return new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")))
                    .with("X-Device-Id", device)
                    .with("X-Platform", "android")
                    .bearer(FakeClerk.token(clerkId, clock.instant()).sign());
        }

        Take reserve() {
            String key = newKey();
            Api.Reply reply =
                    api().post("/v1/sessions", "{\"take_key\":\"" + key + "\",\"topic_id\":\"" + TOPIC + "\"}");
            if (reply.status() == 403) { // the day's free session is used: the next test day
                clock.advance(Duration.ofDays(1));
                return reserve();
            }
            assertThat(reply.status()).isEqualTo(201);
            return new Take(key, UUID.fromString(reply.text("session_id")), this);
        }

        Api.Reply analyze(Take take, byte[] audio, int phoneMs) {
            return analyze(take, audio, phoneMs, false);
        }

        Api.Reply analyze(Take take, byte[] audio, int phoneMs, boolean hitCap) {
            String meta = "{\"session_id\":\"" + take.session() + "\",\"take_key\":\"" + take.key()
                    + "\",\"duration_ms\":" + phoneMs + ",\"hit_cap\":" + hitCap
                    + ",\"interrupted\":false,\"quiet_hint\":false}";
            return api().with("Idempotency-Key", take.key()).postAudio("/v1/analyze", audio, meta);
        }

        int sessionsLeft() {
            return ((Number) api().get("/v1/me").value("quota.sessions_left")).intValue();
        }

        UserId id() {
            return new UserId(jdbc.sql("SELECT id FROM users WHERE clerk_user_id = :c")
                    .param("c", clerkId)
                    .query(UUID.class)
                    .single());
        }
    }

    private Person newPerson(boolean consent) {
        Person person = new Person();
        Api.Reply me = person.api().get("/v1/me");
        assertThat(me.status()).isEqualTo(200);
        if (consent) {
            String version = me.text("consent.policy_version");
            assertThat(person.api()
                            .put("/v1/me/consent", "{\"ai\":true,\"policy_version\":\"" + version + "\"}")
                            .status())
                    .isEqualTo(204);
        }
        return person;
    }

    private String state(UUID session) {
        return jdbc.sql("SELECT state FROM practice_sessions WHERE id = :s")
                .param("s", session)
                .query(String.class)
                .single();
    }

    private int count(String sql, UUID session) {
        return jdbc.sql(sql).param("s", session).query(Integer.class).single();
    }

    private static String newKey() {
        return "t_" + UUID.randomUUID().toString().replace("-", "");
    }
}
