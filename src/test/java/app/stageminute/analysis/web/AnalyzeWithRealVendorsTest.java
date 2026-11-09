package app.stageminute.analysis.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.analysis.internal.M4aFiles;
import app.stageminute.support.Api;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.FakeClerk;
import app.stageminute.support.MutableClock;
import app.stageminute.topics.Level;
import app.stageminute.topics.Style;
import app.stageminute.topics.Topic;
import app.stageminute.topics.TopicCatalog;
import app.stageminute.topics.TopicSettings;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /v1/analyze} over real HTTP with the REAL Deepgram and Gemini clients, pointed at a local WireMock and
 * never at a vendor (#19.1; 03 F-029, F-091, F-122; 05 F-080): a good take is scored and stamped with the real prompt
 * and schema versions; a vendor failure answers {@code 502 analysis_unavailable} and gives the session back; the 45 s
 * budget is one budget for both calls; and no word, prompt text, vendor body or key reaches a log line.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(AnalyzeWithRealVendorsTest.Doubles.class)
@TestPropertySource(
        properties = {
            "stageminute.analysis.fake-vendors=false",
            "spring.profiles.active=test",
            "stageminute.vendors.deepgram.api-key=" + AnalyzeWithRealVendorsTest.DG_KEY,
            "stageminute.vendors.gemini.api-key=" + AnalyzeWithRealVendorsTest.GM_KEY,
            "logging.level.root=DEBUG"
        })
class AnalyzeWithRealVendorsTest extends DatabaseTest {

    static final String DG_KEY = "dg_e2e_key_not_real_7781";
    static final String GM_KEY = "gm_e2e_key_not_real_9942";
    private static final String TOPIC = "prep-ele-001";
    private static final String PROMPT_TEXT = "PROMPT-TEXT-55aa01 What made your week?";
    private static final String SPOKEN_MARK = "quokkamark";
    private static final String VENDOR_BODY_MARK = "VENDOR-BODY-MARK-31ce";
    private static final String GEMINI_PATH = "/v1beta/models/gemini-3.8-flash:generateContent";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final WireMockServer WIRE =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIRE.start();
    }

    @DynamicPropertySource
    static void vendorsOnTheLocalWire(DynamicPropertyRegistry registry) {
        registry.add("stageminute.vendors.deepgram.base-url", () -> "http://localhost:" + WIRE.port());
        registry.add("stageminute.vendors.gemini.base-url", () -> "http://localhost:" + WIRE.port());
    }

    @AfterAll
    static void stopWire() {
        WIRE.stop();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Doubles {
        @Bean
        @Primary
        TopicCatalog realVendorsCatalog() {
            Topic topic = new Topic(TOPIC, Style.PREP, Level.ELEMENTARY, 1, PROMPT_TEXT, null, 30, 60, true, false);
            return TopicCatalog.of(List.of(topic), TopicSettings.LAUNCH_DEFAULT);
        }
    }

    @Autowired
    Environment env;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry meters;

    @BeforeEach
    void reset() {
        WIRE.resetAll();
        jdbc.sql("DELETE FROM app_settings WHERE key = 'analysis_budget_ms' OR key LIKE 'ai!_%' ESCAPE '!'")
                .update();
        jdbc.sql("DELETE FROM ai_spend_daily").update();
        // Each test starts ten minutes after the one before, on the same UTC day: a call that ended with no usage
        // report
        // stays counted for five minutes (the exposure window), so one test's failed calls cannot count in the next.
        clock.advance(Duration.between(
                clock.instant(), Instant.parse("2026-10-29T10:00:00Z").plus(Duration.ofMinutes(10L * RUNS++))));
    }

    private static int RUNS = 0;

    /** 120 words, 0.4 s each, a sentence every 12 words: a take that clears the no-score gate. */
    private static String goodDeepgramAnswer() {
        StringBuilder words = new StringBuilder();
        StringBuilder sentences = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            double start = 1.0 + i * 0.4;
            words.append(i == 0 ? "" : ",")
                    .append("{\"word\":\"")
                    .append(SPOKEN_MARK)
                    .append("\",\"punctuated_word\":\"")
                    .append(SPOKEN_MARK)
                    .append(i % 12 == 11 ? "." : "")
                    .append("\",\"start\":")
                    .append(start)
                    .append(",\"end\":")
                    .append(start + 0.35)
                    .append(",\"confidence\":0.97}");
            if (i % 12 == 11) {
                sentences
                        .append(sentences.isEmpty() ? "" : ",")
                        .append("{\"text\":\"x\",\"start\":")
                        .append(start - 11 * 0.4)
                        .append(",\"end\":")
                        .append(start + 0.35)
                        .append("}");
            }
        }
        return "{\"metadata\":{\"duration\":55.0,\"model_info\":{\"m\":{\"name\":\"general-nova-3\"}}},"
                + "\"results\":{\"channels\":[{\"detected_language\":\"en\",\"alternatives\":[{\"words\":["
                + words
                + "],\"paragraphs\":{\"paragraphs\":[{\"sentences\":["
                + sentences
                + "]}]}}]}]}}";
    }

    private static String goodGeminiAnswer(String modelVersion) {
        String labels = "{\"steps\":[\"strong\",\"strong\",\"weak\",\"strong\"],\"step_sentences\":[0,1,2,9],"
                + "\"relevance\":4,\"clarity\":3,\"conciseness\":3,\"accuracy\":4,\"fillers\":[],\"run_on\":false,"
                + "\"verdict\":\"Clear point and a clean closing.\",\"try_line\":\"Give your example a name.\","
                + "\"rewrite\":null,\"strongest_sentence\":1,\"strongest_why\":\"You named it plainly.\","
                + "\"language_items\":[]}";
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + JSON.writeValueAsString(labels)
                + "}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":1000,"
                + "\"candidatesTokenCount\":150},\"modelVersion\":\"" + modelVersion + "\"}";
    }

    private void deepgramAnswers(int status, String body, int delayMs) {
        WIRE.stubFor(post(urlPathEqualTo("/v1/listen"))
                .willReturn(aResponse().withStatus(status).withBody(body).withFixedDelay(delayMs)));
    }

    private void geminiAnswers(int status, String body, int delayMs) {
        WIRE.stubFor(post(urlPathEqualTo(GEMINI_PATH))
                .willReturn(aResponse().withStatus(status).withBody(body).withFixedDelay(delayMs)));
    }

    private int deepgramCalls() {
        return WIRE.findAll(postRequestedFor(urlPathEqualTo("/v1/listen"))).size();
    }

    private int geminiCalls() {
        return WIRE.findAll(postRequestedFor(urlPathEqualTo(GEMINI_PATH))).size();
    }

    private record Person(String clerk, Api api) {}

    private Person person() {
        String clerk = "user_" + UUID.randomUUID().toString().replace("-", "");
        Api api = new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")))
                .with("X-Device-Id", "device-" + UUID.randomUUID())
                .with("X-Platform", "android")
                .bearer(FakeClerk.token(clerk, clock.instant()).sign());
        Api.Reply me = api.get("/v1/me");
        assertThat(api.put(
                                "/v1/me/consent",
                                "{\"ai\":true,\"policy_version\":\"" + me.text("consent.policy_version") + "\"}")
                        .status())
                .isEqualTo(204);
        return new Person(clerk, api);
    }

    private record Take(String key, UUID session) {}

    private Take reserve(Person person) {
        String key = "t_" + UUID.randomUUID().toString().replace("-", "");
        Api.Reply reply =
                person.api().post("/v1/sessions", "{\"take_key\":\"" + key + "\",\"topic_id\":\"" + TOPIC + "\"}");
        assertThat(reply.status()).isEqualTo(201);
        return new Take(key, UUID.fromString(reply.text("session_id")));
    }

    private Api.Reply analyze(Person person, Take take) {
        String meta = "{\"session_id\":\"" + take.session() + "\",\"take_key\":\"" + take.key()
                + "\",\"duration_ms\":55000,\"hit_cap\":false,\"interrupted\":false,\"quiet_hint\":false}";
        return person.api()
                .with("Idempotency-Key", take.key())
                .postAudio("/v1/analyze", M4aFiles.ofLength(55_000), meta);
    }

    private int sessionsLeft(Person person) {
        return ((Number) person.api().get("/v1/me").value("quota.sessions_left")).intValue();
    }

    private String state(UUID session) {
        return jdbc.sql("SELECT state FROM practice_sessions WHERE id = :s")
                .param("s", session)
                .query(String.class)
                .single();
    }

    @Test
    void aGoodTakeIsScoredByTheRealClientsAndStampedWithTheRealPromptAndSchemaVersions() {
        deepgramAnswers(200, goodDeepgramAnswer(), 0);
        geminiAnswers(200, goodGeminiAnswer("gemini-3.8-flash"), 0);
        Person person = person();
        Take take = reserve(person);

        Api.Reply reply = analyze(person, take);

        assertThat(reply.status()).isEqualTo(200);
        JsonNode report = JSON.readTree(reply.body()).path("report");
        assertThat(report.path("status").asString()).isEqualTo("ok");
        assertThat(report.path("model_version").asString()).isEqualTo("gemini-3.8-flash");
        assertThat(report.path("prompt_version").asString()).matches("v2-[0-9a-f]{8}");
        assertThat(report.path("schema_version").asString()).matches("v2-[0-9a-f]{8}");
        assertThat(report.path("score").path("total").asInt()).isPositive();
        assertThat(deepgramCalls()).isEqualTo(1);
        assertThat(geminiCalls()).isEqualTo(1);
        WIRE.verify(postRequestedFor(urlPathEqualTo("/v1/listen"))
                .withQueryParam("mip_opt_out", equalTo("true"))
                .withQueryParam("filler_words", equalTo("true")));
        assertThat(state(take.session())).isEqualTo("committed");
    }

    @Test
    void aDeepgramFailureAnswers502AndGivesTheSessionBackWithoutCallingGemini() {
        deepgramAnswers(503, "{\"err\":\"" + VENDOR_BODY_MARK + "\"}", 0);
        geminiAnswers(200, goodGeminiAnswer("gemini-3.8-flash"), 0);
        Person person = person();
        Take take = reserve(person);
        assertThat(sessionsLeft(person)).isZero();

        Api.Reply reply = analyze(person, take);

        assertThat(reply.status()).isEqualTo(502);
        assertThat(reply.text("reason")).isEqualTo("analysis_unavailable");
        assertThat(reply.value("session_given_back")).isEqualTo(true);
        assertThat(sessionsLeft(person)).isEqualTo(1);
        assertThat(state(take.session())).isEqualTo("released");
        assertThat(deepgramCalls()).as("an HTTP answer is never retried").isEqualTo(1);
        assertThat(geminiCalls()).isZero();
    }

    @Test
    void geminiInvalidJsonIsRetriedOnceThen502AndTheSessionIsGivenBack() {
        deepgramAnswers(200, goodDeepgramAnswer(), 0);
        geminiAnswers(
                200,
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"not json\"}]},\"finishReason\":\"STOP\"}]}",
                0);
        Person person = person();
        Take take = reserve(person);

        Api.Reply reply = analyze(person, take);

        assertThat(reply.status()).isEqualTo(502);
        assertThat(reply.text("reason")).isEqualTo("analysis_unavailable");
        assertThat(reply.value("session_given_back")).isEqualTo(true);
        assertThat(geminiCalls()).as("invalid JSON: one retry, no more").isEqualTo(2);
        assertThat(deepgramCalls()).isEqualTo(1);
        assertThat(sessionsLeft(person)).isEqualTo(1);
    }

    @Test
    void aGeminiTimeoutIsNotRetriedAndGetsOnlyTheTimeLeftOfOneBudgetForBothCalls() {
        jdbc.sql("INSERT INTO app_settings (key, value, version, updated_at, updated_by)"
                        + " VALUES ('analysis_budget_ms', '2500'::jsonb, 1, :t, 'test')")
                .param("t", OffsetDateTime.parse("2026-10-29T10:00:00Z"))
                .update();
        deepgramAnswers(200, goodDeepgramAnswer(), 1200);
        geminiAnswers(200, goodGeminiAnswer("gemini-3.8-flash"), 6000);
        Person person = person();
        Take take = reserve(person);

        long started = System.nanoTime();
        Api.Reply reply = analyze(person, take);
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(reply.status()).isEqualTo(502);
        assertThat(reply.value("session_given_back")).isEqualTo(true);
        assertThat(geminiCalls()).as("a time-out is never retried").isEqualTo(1);
        assertThat(tookMs)
                .as("one budget of 2.5 s for both calls, not 2.5 s each")
                .isLessThan(3300);
    }

    @Test
    void noWordPromptTextVendorBodyOrKeyReachesALogLineOrAnErrorBody(CapturedOutput output) {
        deepgramAnswers(200, goodDeepgramAnswer(), 0);
        geminiAnswers(500, "{\"error\":\"" + VENDOR_BODY_MARK + "\"}", 0);
        Person person = person();
        Take take = reserve(person);

        Api.Reply reply = analyze(person, take);

        assertThat(reply.status()).isEqualTo(502);
        assertThat(reply.body()).doesNotContain(SPOKEN_MARK, VENDOR_BODY_MARK, "PROMPT-TEXT", DG_KEY, GM_KEY);
        assertThat(output.getAll()).doesNotContain(SPOKEN_MARK, VENDOR_BODY_MARK, "PROMPT-TEXT-55aa01", DG_KEY, GM_KEY);
    }

    // ---- the answered model id (#19.1, industry-research item) ----

    private String stored(UUID session, String column) {
        return jdbc.sql("SELECT " + column + " FROM reports WHERE session_id = :s")
                .param("s", session)
                .query(String.class)
                .single();
    }

    @Test
    void theModelIdsTheVendorsAnsweredWithAreStoredBesideThePinnedNameAndNeverInTheApi() {
        deepgramAnswers(200, goodDeepgramAnswer(), 0);
        geminiAnswers(200, goodGeminiAnswer("gemini-3.8-flash"), 0);
        Person person = person();
        Take take = reserve(person);

        Api.Reply reply = analyze(person, take);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(stored(take.session(), "model_version")).isEqualTo("gemini-3.8-flash");
        assertThat(stored(take.session(), "answered_judge_model")).isEqualTo("gemini-3.8-flash");
        assertThat(stored(take.session(), "answered_stt_model")).isEqualTo("general-nova-3");
        String id =
                JSON.readTree(reply.body()).path("report").path("analysis_id").asString();
        assertThat(reply.body()).doesNotContain("answered");
        Api.Reply later = person.api().get("/v1/analyses/" + id);
        assertThat(later.status()).isEqualTo(200);
        assertThat(later.body()).doesNotContain("answered");
        assertThat(person.api().get("/v1/analyses").body()).doesNotContain("answered");
    }

    @Test
    void aChangedAnsweredModelIsStoredCountedAndLoggedWithIdsOnly(CapturedOutput output) {
        double before =
                meters.counter("vendor.model.mismatch", "vendor", "gemini").count();
        deepgramAnswers(200, goodDeepgramAnswer(), 0);
        geminiAnswers(200, goodGeminiAnswer("gemini-3.8-flash-002"), 0);
        Person person = person();
        Take take = reserve(person);

        Api.Reply reply = analyze(person, take);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(stored(take.session(), "model_version")).isEqualTo("gemini-3.8-flash"); // the pinned name
        assertThat(stored(take.session(), "answered_judge_model")).isEqualTo("gemini-3.8-flash-002");
        assertThat(meters.counter("vendor.model.mismatch", "vendor", "gemini").count())
                .isEqualTo(before + 1);
        assertThat(output.getAll())
                .contains("vendor.model_mismatch vendor=gemini pinned=gemini-3.8-flash answered=gemini-3.8-flash-002");
    }

    // ---- the spend meter on the real clients (#19.2; 03 F-087, F-122) ----

    private static final String TODAY = "2026-10-29";

    private long spendMicros() {
        return jdbc.sql("SELECT coalesce(sum(micros), 0) FROM ai_spend_daily WHERE utc_date = :d::date")
                .param("d", TODAY)
                .query(Long.class)
                .single();
    }

    private int spendCalls() {
        return jdbc.sql("SELECT coalesce(sum(calls), 0) FROM ai_spend_daily WHERE utc_date = :d::date")
                .param("d", TODAY)
                .query(Integer.class)
                .single();
    }

    /** Spend already on the day's row, as if other people had used the vendors. */
    private void daySpendIs(long micros) {
        jdbc.sql("INSERT INTO ai_spend_daily (utc_date, micros, calls) VALUES (:d::date, :m, 1)"
                        + " ON CONFLICT (utc_date) DO UPDATE SET micros = :m")
                .param("d", clock.instant().toString().substring(0, 10)) // the UTC date the clock shows
                .param("m", micros)
                .update();
    }

    private void budgetIsOneDollar() {
        jdbc.sql("INSERT INTO app_settings (key, value, version, updated_at, updated_by)"
                        + " VALUES ('ai_daily_budget_usd', '1'::jsonb, 1, now(), 'test')")
                .update();
    }

    private int sessionsUsed(Person person) {
        return jdbc.sql("SELECT coalesce(sum(c.sessions_used), 0) FROM daily_counters c"
                        + " JOIN users u ON u.id = c.user_id WHERE u.clerk_user_id = :c")
                .param("c", person.clerk())
                .query(Integer.class)
                .single();
    }

    @Test
    void aScoredTakeAddsItsTwoCallsToTheDaysSpendAtThePublishedPrices() {
        deepgramAnswers(200, goodDeepgramAnswer(), 0);
        geminiAnswers(200, goodGeminiAnswer("gemini-3.8-flash"), 0);
        Person person = person();
        Take take = reserve(person);

        assertThat(analyze(person, take).status()).isEqualTo(200);

        // Deepgram: 55 s at USD 0.0052 a minute = 4 766.67, rounded up to 4 767 micros.
        // Gemini: 1 000 tokens in at USD 1.50 per million = 1 500; 150 out at USD 7.50 per million = 1 125 micros.
        assertThat(spendMicros()).isEqualTo(4_767 + 1_500 + 1_125);
        assertThat(spendCalls()).isEqualTo(2);
    }

    @Test
    void aThrottleThatBeginsAfterTheSessionWasReservedStillLetsTheAnalysisFinish() {
        deepgramAnswers(200, goodDeepgramAnswer(), 0);
        geminiAnswers(200, goodGeminiAnswer("gemini-3.8-flash"), 0);
        budgetIsOneDollar();
        Person person = person();
        Take take = reserve(person); // the gate said yes: the day was empty
        daySpendIs(1_100_000); // 110 % of the USD 1 budget: free people are paused from now on, the stop is at 160 %

        Api.Reply reply = analyze(person, take);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(state(take.session())).isEqualTo("committed");
        assertThat(geminiCalls()).isEqualTo(1);
    }

    @Test
    void aVendorCallTheMeterRefusesEndsAs503ThrottledAndGivesTheSessionBack() {
        deepgramAnswers(200, goodDeepgramAnswer(), 0);
        geminiAnswers(200, goodGeminiAnswer("gemini-3.8-flash"), 0);
        budgetIsOneDollar();
        Person person = person();
        Take take = reserve(person);
        daySpendIs(1_550_000); // 155 %: Deepgram still fits under the stop of 160 %, Gemini's worst case does not
        double breakerBefore = meters.counter("vendor.breaker.transitions", "vendor", "gemini", "to", "open")
                .count();

        Api.Reply reply = analyze(person, take);

        assertThat(reply.status()).isEqualTo(503);
        assertThat(reply.text("reason")).isEqualTo("throttled");
        assertThat(reply.value("session_given_back")).isEqualTo(true); // "This doesn't use a free session" (v8-15h)
        assertThat(reply.text("resumes_at")).isEqualTo("2026-10-30T00:00:00Z");
        assertThat(state(take.session())).isEqualTo("released");
        assertThat(sessionsUsed(person)).isZero();
        assertThat(deepgramCalls()).isEqualTo(1);
        assertThat(geminiCalls()).as("a refused call is never sent").isZero();
        assertThat(reply.body()).doesNotContain("micros", "1550000", "budget");
        assertThat(meters.counter("vendor.breaker.transitions", "vendor", "gemini", "to", "open")
                        .count())
                .as("a throttle refusal is not a vendor failure")
                .isEqualTo(breakerBefore);
    }

    @Test
    void theSpendLogLinesCarryLevelsAndPercentsOnlyNeverAPersonOrAnAmount(CapturedOutput output) {
        deepgramAnswers(200, goodDeepgramAnswer(), 0);
        geminiAnswers(200, goodGeminiAnswer("gemini-3.8-flash"), 0);
        budgetIsOneDollar();
        clock.advance(Duration.ofDays(2)); // a UTC day of its own: each alert level fires once a day, once per server
        daySpendIs(695_000); // 69.5 %: this take's own cost takes the day over the 70 % alert level
        Person first = person();
        assertThat(analyze(first, reserve(first)).status()).isEqualTo(200);
        Person second = person();
        Take refusedTake = reserve(second);
        daySpendIs(1_550_000);
        assertThat(analyze(second, refusedTake).status()).isEqualTo(503);

        String userId = jdbc.sql("SELECT id::text FROM users WHERE clerk_user_id = :c")
                .param("c", second.clerk())
                .query(String.class)
                .single();
        List<String> spendLines = output.getAll()
                .lines()
                .filter(line -> line.contains("ai.spend") || line.contains("analyze.throttled"))
                .toList();
        assertThat(spendLines)
                .anyMatch(line -> line.contains("ai.spend.level_reached level=alert percent="))
                .anyMatch(line -> line.contains("analyze.throttled code=spend_stop given_back=true"));
        // The day-level alerts say how far the day is, never who was on the request that crossed the line.
        assertThat(spendLines.stream().filter(line -> line.contains("ai.spend.level_reached")))
                .isNotEmpty()
                .noneMatch(line -> line.contains("\"userId\"") || line.contains(userId))
                .noneMatch(line -> line.contains("\"requestId\""));
        // The per-request line has the opaque id (rule 4) and an event name, but no amount.
        assertThat(spendLines)
                .noneMatch(line -> line.contains(first.clerk()) || line.contains(second.clerk()))
                .noneMatch(line -> line.contains(SPOKEN_MARK) || line.contains("PROMPT-TEXT") || line.contains(DG_KEY))
                .noneMatch(line -> line.contains("micros") || line.contains("1550000"));
    }
}
