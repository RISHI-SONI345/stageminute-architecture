package app.stageminute.analysis.vendor;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.stageminute.analysis.score.FillerCandidate;
import app.stageminute.analysis.score.FillerKind;
import app.stageminute.analysis.score.FillerLabel;
import app.stageminute.analysis.score.SentenceSpan;
import app.stageminute.analysis.score.StepStatus;
import app.stageminute.analysis.score.Style;
import app.stageminute.analysis.score.TimedWord;
import app.stageminute.analysis.score.Transcript;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real Gemini judge against a local WireMock, never the real vendor (#19.1; 03 F-029, F-122, D-84). One call per
 * take; invalid output is retried once; a time-out, a network error or an HTTP answer never is.
 */
class GeminiJudgeTest {

    private static final String KEY = "gm_test_not_a_real_key";
    private static final String MODEL = "gemini-3.8-flash";
    private static final String PATH = "/v1beta/models/" + MODEL + ":generateContent";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** What the model returns when everything is right. */
    static final String LABELS = """
            {"steps":["strong","strong","weak","missing"],"step_sentences":[0,0,1,null],
             "relevance":4,"clarity":3,"conciseness":3,"accuracy":4,
             "fillers":[{"id":0,"label":"filler"}],"run_on":false,
             "verdict":"Clear point, no closing.","try_line":"End with one line that sums up.",
             "rewrite":"I like it, and here is why.","strongest_sentence":1,"strongest_why":"You named it plainly.",
             "language_items":[{"kind":"grammar","sentence":1,"first_word":2,"last_word":3,"suggestion":"I like it"}]}
            """;

    private WireMockServer wire;
    private final List<String> events = new ArrayList<>();
    private final MeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void startWire() {
        wire = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wire.start();
    }

    @AfterEach
    void stopWire() {
        wire.stop();
    }

    private static JudgeInput input() {
        List<TimedWord> words = List.of(
                new TimedWord("Hello", 500, 900, 0.99),
                new TimedWord("there.", 900, 1300, 0.98),
                new TimedWord("Um", 2000, 2300, 0.90),
                new TimedWord("I", 2300, 2400, 0.95),
                new TimedWord("like", 2400, 2700, 0.96),
                new TimedWord("it.", 2700, 3000, 0.97));
        Transcript transcript = new Transcript(words, List.of(new SentenceSpan(0, 1), new SentenceSpan(2, 5)), 30_000);
        return new JudgeInput(
                Style.PREP,
                "Describe a tool you like.",
                transcript,
                List.of(new FillerCandidate(0, FillerKind.IN_CONTEXT, 4, 4, 2400)),
                List.of());
    }

    private static String envelope(String labelsJson, String finishReason, String modelVersion) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":"
                + JSON.writeValueAsString(labelsJson)
                + "}]},\"finishReason\":\"" + finishReason + "\"}],"
                + "\"usageMetadata\":{\"promptTokenCount\":900,\"candidatesTokenCount\":200,"
                + "\"thoughtsTokenCount\":50,\"totalTokenCount\":1150},"
                + "\"modelVersion\":\"" + modelVersion + "\"}";
    }

    private static String ok() {
        return envelope(LABELS, "STOP", MODEL);
    }

    private GeminiJudge judge(VendorLimits limits) {
        return judge(limits, new UsageSink() {
            @Override
            public void starting(Vendor vendor, long audioMs) {
                events.add("starting " + vendor.tag());
            }

            @Override
            public void used(VendorUsage usage) {
                events.add("used in=" + usage.inputTokens() + " out=" + usage.outputTokens());
            }

            @Override
            public void noUsageReported(Vendor vendor) {
                events.add("noUsage " + vendor.tag());
            }
        });
    }

    private GeminiJudge judge(VendorLimits limits, UsageSink sink) {
        return new GeminiJudge(
                new VendorEndpoint("http://localhost:" + wire.port(), KEY, MODEL),
                PromptFiles.fromClasspath(),
                new VendorTransport(),
                limits,
                sink,
                new VendorGuard(Vendor.GEMINI, limits, meters),
                meters);
    }

    private JudgeLabels run(VendorLimits limits) {
        return judge(limits).judge(input());
    }

    private void answerWith(int status, String body) {
        wire.stubFor(post(urlPathEqualTo(PATH))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private List<LoggedRequest> calls() {
        return wire.findAll(postRequestedFor(urlPathEqualTo(PATH)));
    }

    // ---- the request ----

    @Test
    void aThinkingBudgetSettingIsSentAsThinkingConfigAndNothingIsSentWithoutIt() {
        answerWith(200, ok());

        run(TestLimits.launch().withThinkingBudget(512));

        JsonNode config = JSON.readTree(calls().get(0).getBodyAsString()).path("generationConfig");
        assertThat(config.path("thinkingConfig").path("thinkingBudget").asInt()).isEqualTo(512);
        assertThat(config.path("thinkingConfig").size()).isEqualTo(1);
    }

    @Test
    void oneCallWithThePinnedModelThePromptTheEnumSchemaAndTheMostRepeatableSettings() {
        answerWith(200, ok());

        run(TestLimits.launch());

        assertThat(calls()).hasSize(1);
        wire.verify(postRequestedFor(urlPathEqualTo(PATH))
                .withHeader("x-goog-api-key", equalTo(KEY))
                .withHeader("Content-Type", equalTo("application/json")));
        LoggedRequest sent = calls().get(0);
        assertThat(sent.getUrl()).doesNotContain(KEY).doesNotContain("key=");
        JsonNode body = JSON.readTree(sent.getBodyAsString());
        JsonNode config = body.path("generationConfig");
        assertThat(config.path("temperature").asDouble()).isZero();
        assertThat(config.has("candidateCount")).isFalse();
        assertThat(config.has("thinkingConfig")).isFalse();
        assertThat(config.path("seed").isNumber()).isTrue();
        assertThat(config.path("responseMimeType").asString()).isEqualTo("application/json");
        assertThat(config.path("responseJsonSchema").path("properties").has("steps"))
                .isTrue();
        assertThat(config.path("responseJsonSchema")
                        .path("properties")
                        .path("steps")
                        .path("items")
                        .path("enum")
                        .size())
                .isEqualTo(3);
        assertThat(body.path("systemInstruction")
                        .path("parts")
                        .path(0)
                        .path("text")
                        .asString())
                .isEqualTo(PromptFiles.fromClasspath().prompt());
    }

    @Test
    void theTakeIsSentAsTextOnlyWithWordPositionsSentencesAndFillerCandidatesAndNoAudio() {
        answerWith(200, ok());

        run(TestLimits.launch());

        JsonNode body = JSON.readTree(calls().get(0).getBodyAsString());
        JsonNode parts = body.path("contents").path(0).path("parts");
        assertThat(parts.size()).isEqualTo(1);
        assertThat(parts.path(0).has("inlineData")).isFalse();
        assertThat(parts.path(0).has("fileData")).isFalse();
        JsonNode sent = JSON.readTree(parts.path(0).path("text").asString());
        assertThat(sent.path("style").asString()).isEqualTo("PREP");
        assertThat(sent.path("topic").asString()).isEqualTo("Describe a tool you like.");
        assertThat(sent.path("sentences").size()).isEqualTo(2);
        assertThat(sent.path("sentences").path(1).path("first_word").asInt()).isEqualTo(2);
        assertThat(sent.path("sentences").path(1).path("words").path(2).asString())
                .isEqualTo("like");
        assertThat(sent.path("filler_candidates").path(0).path("id").asInt()).isZero();
        assertThat(sent.path("filler_candidates").path(0).path("text").asString())
                .isEqualTo("like");
        assertThat(sent.path("filler_candidates").path(0).path("sentence").asInt())
                .isEqualTo(1);
    }

    @Test
    void thePinnedModelNameAndTheVersionsAreWhatTheReportsAreStamped() {
        GeminiJudge judge = judge(TestLimits.launch());
        PromptFiles.Loaded files = PromptFiles.fromClasspath();

        assertThat(judge.versions().model()).isEqualTo(MODEL);
        assertThat(judge.versions().prompt()).isEqualTo(files.promptVersion());
        assertThat(judge.versions().schema()).isEqualTo(files.schemaVersion());
        assertThat(judge.versions().prompt()).doesNotContain("fake");
    }

    // ---- what the answer becomes ----

    @Test
    void validEnumJsonBecomesJudgeLabelsWithTheAnsweredModel() {
        answerWith(200, ok());

        JudgeLabels labels = run(TestLimits.launch());

        assertThat(labels.steps())
                .containsExactly(StepStatus.STRONG, StepStatus.STRONG, StepStatus.WEAK, StepStatus.MISSING);
        assertThat(labels.stepSentences()).containsExactly(0, 0, 1, null);
        assertThat(labels.relevance()).isEqualTo(4);
        assertThat(labels.language().clarity()).isEqualTo(3);
        assertThat(labels.language().conciseness()).isEqualTo(3);
        assertThat(labels.language().accuracy()).isEqualTo(4);
        assertThat(labels.fillers()).isEqualTo(Map.of(0, FillerLabel.FILLER));
        assertThat(labels.runOn()).isFalse();
        assertThat(labels.verdict()).isEqualTo("Clear point, no closing.");
        assertThat(labels.strongestSentence()).isEqualTo(OptionalInt.of(1));
        assertThat(labels.languageItems()).hasSize(1);
        assertThat(labels.languageItems().get(0).kind()).isEqualTo("grammar");
        assertThat(labels.answeredModel()).isEqualTo(MODEL);
    }

    @Test
    void modelTextThatHoldsANumberIsPassedOnUntouchedBecauseTheValidatorLivesInThePipeline() {
        answerWith(200, envelope(LABELS.replace("Clear point, no closing.", "A solid 95 out of 100"), "STOP", MODEL));

        JudgeLabels labels = run(TestLimits.launch());

        assertThat(labels.verdict()).isEqualTo("A solid 95 out of 100"); // LabelValidator drops it later (rule 5)
    }

    @Test
    void nullLinesStayNull() {
        String quiet = LABELS.replace("\"verdict\":\"Clear point, no closing.\"", "\"verdict\":null")
                .replace("\"strongest_sentence\":1", "\"strongest_sentence\":null");
        answerWith(200, envelope(quiet, "STOP", MODEL));

        JudgeLabels labels = run(TestLimits.launch());

        assertThat(labels.verdict()).isNull();
        assertThat(labels.strongestSentence()).isEqualTo(OptionalInt.empty());
    }

    // ---- invalid output: retried once ----

    private static List<String> invalidAnswers() {
        return List.of(
                envelope("this is not json SECRET-WORDS", "STOP", MODEL),
                envelope("", "STOP", MODEL),
                envelope(LABELS.replace("\"weak\"", "\"great\""), "STOP", MODEL), // a value outside the enum
                envelope(LABELS.replace("\"relevance\":4,", ""), "STOP", MODEL), // a missing field
                envelope(LABELS.replace("\"relevance\":4", "\"relevance\":7"), "STOP", MODEL), // out of range
                envelope(LABELS.replace("\"run_on\":false", "\"run_on\":\"no\""), "STOP", MODEL), // wrong type
                envelope(LABELS.replace("\"relevance\":4,", "\"relevance\":4,\"score\":93,"), "STOP", MODEL), // extra
                envelope(LABELS.replace("[\"strong\",\"strong\",\"weak\",\"missing\"]", "[\"strong\"]"), "STOP", MODEL),
                envelope(LABELS.replace("[0,0,1,null]", "[0,0,9,null]"), "STOP", MODEL), // a sentence that is not there
                envelope(LABELS.replace("\"id\":0", "\"id\":5"), "STOP", MODEL), // a candidate that was not sent
                envelope(LABELS.replace("\"strongest_sentence\":1", "\"strongest_sentence\":8"), "STOP", MODEL),
                envelope(LABELS, "SAFETY", MODEL),
                envelope(LABELS, "MAX_TOKENS", MODEL),
                "{\"candidates\":[]}",
                "{}",
                "[]",
                "not json at all");
    }

    @Test
    void anyInvalidAnswerIsRetriedExactlyOnceAndThenFailsWithAFixedCode() {
        for (String body : invalidAnswers()) {
            wire.resetRequests();
            answerWith(200, body);

            assertThatThrownBy(() -> run(TestLimits.launch()))
                    .isInstanceOf(VendorUnavailable.class)
                    .hasMessage("llm_invalid_output")
                    .satisfies(e -> assertThat(e.getCause()).isNull());
            assertThat(calls()).as("answer %s", body).hasSize(2);
        }
    }

    @Test
    void anInvalidFirstAnswerFollowedByAValidOneGivesTheLabelsAfterTwoCalls() {
        wire.stubFor(post(urlPathEqualTo(PATH))
                .inScenario("repair")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withBody(envelope("{", "STOP", MODEL)))
                .willSetStateTo("second"));
        wire.stubFor(post(urlPathEqualTo(PATH))
                .inScenario("repair")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200).withBody(ok())));

        JudgeLabels labels = run(TestLimits.launch());

        assertThat(labels.relevance()).isEqualTo(4);
        assertThat(calls()).hasSize(2);
        assertThat(meters.counter("vendor.retry", "vendor", "gemini", "outcome", "invalid_output")
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void theInvalidRetryHappensOnlyWhenTheBudgetHasTheRetryMinimumLeft() {
        answerWith(200, envelope("{", "STOP", MODEL));

        VendorBudget.within(
                3000,
                () -> assertThatThrownBy(() -> run(TestLimits.launch().withRetryMinRemainingMs(5000)))
                        .isInstanceOf(VendorUnavailable.class)
                        .hasMessage("llm_invalid_output"));

        assertThat(calls()).hasSize(1);
    }

    // ---- everything else is not retried: every retry costs money ----

    @Test
    void aTimeoutIsNotRetried() {
        wire.stubFor(post(urlPathEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withBody(ok()).withFixedDelay(3000)));

        assertThatThrownBy(() -> run(TestLimits.launch().withBudgetMs(400)))
                .isInstanceOf(VendorUnavailable.class)
                .hasMessage("llm_timeout");
        assertThat(calls()).hasSize(1);
    }

    @Test
    void anHttpErrorIsNotRetried() {
        for (int status : new int[] {400, 401, 403, 404, 429, 500, 503}) {
            wire.resetRequests();
            answerWith(status, "{\"error\":{\"message\":\"SECRET-VENDOR-TEXT\"}}");

            assertThatThrownBy(() -> run(TestLimits.launch()))
                    .isInstanceOf(VendorUnavailable.class)
                    .hasMessage(status >= 500 ? "llm_http_5xx" : status == 429 ? "llm_http_429" : "llm_http_4xx");
            assertThat(calls()).as("status %s", status).hasSize(1);
        }
    }

    @Test
    void aNetworkErrorIsNotRetried() {
        wire.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> run(TestLimits.launch()))
                .isInstanceOf(VendorUnavailable.class)
                .hasMessage("llm_network");
        assertThat(calls()).hasSize(1);
    }

    @Test
    void theCallGetsOnlyTheTimeLeftOfTheBudget() {
        wire.stubFor(post(urlPathEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withBody(ok()).withFixedDelay(1500)));

        long started = System.nanoTime();
        VendorBudget.within(
                400,
                () -> assertThatThrownBy(() -> run(TestLimits.launch()))
                        .isInstanceOf(VendorUnavailable.class)
                        .hasMessage("llm_timeout"));

        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1400);
    }

    // ---- units, the answered model ----

    @Test
    void everyCallReportsTokensIncludingThinkingAndAnInvalidAnswerStillCostsTokens() {
        answerWith(200, ok());
        run(TestLimits.launch());
        assertThat(events).containsExactly("starting gemini", "used in=900 out=250");

        events.clear();
        answerWith(200, envelope("{", "STOP", MODEL));
        assertThatThrownBy(() -> run(TestLimits.launch())).isInstanceOf(VendorUnavailable.class);
        assertThat(events)
                .containsExactly("starting gemini", "used in=900 out=250", "starting gemini", "used in=900 out=250");
    }

    @Test
    void aFailureWithoutAnAnswerReportsNoUsage() {
        answerWith(503, "{}");

        assertThatThrownBy(() -> run(TestLimits.launch())).isInstanceOf(VendorUnavailable.class);

        assertThat(events).containsExactly("starting gemini", "noUsage gemini");
    }

    @Test
    void aSinkThatRefusesStopsTheCallBeforeAnythingIsSent() {
        UsageSink refusing = new UsageSink() {
            @Override
            public void starting(Vendor vendor, long audioMs) {
                throw new VendorUnavailable("spend_paused");
            }
        };

        assertThatThrownBy(() -> judge(TestLimits.launch(), refusing).judge(input()))
                .isInstanceOf(VendorUnavailable.class)
                .hasMessage("spend_paused");
        assertThat(wire.getAllServeEvents()).isEmpty();
    }

    @Test
    void anAnsweredModelThatIsNotThePinnedOneIsCountedAndKeptOnTheLabels() {
        answerWith(200, envelope(LABELS, "STOP", "gemini-3.8-flash-002"));

        JudgeLabels labels = run(TestLimits.launch());

        assertThat(labels.answeredModel()).isEqualTo("gemini-3.8-flash-002");
        assertThat(meters.counter("vendor.model.mismatch", "vendor", "gemini").count())
                .isEqualTo(1.0);
    }

    @Test
    void theAnsweredModelWithTheModelsPrefixMatchesAndIsNotCounted() {
        answerWith(200, envelope(LABELS, "STOP", "models/" + MODEL));

        JudgeLabels labels = run(TestLimits.launch());

        assertThat(labels.answeredModel()).isEqualTo(MODEL);
        assertThat(meters.counter("vendor.model.mismatch", "vendor", "gemini").count())
                .isZero();
    }

    @Test
    void theKeyNeverAppearsInAnError() {
        wire.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

        assertThatThrownBy(() -> run(TestLimits.launch()))
                .isInstanceOf(VendorUnavailable.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain(KEY);
                    assertThat(e.getCause()).isNull();
                });
    }
}
