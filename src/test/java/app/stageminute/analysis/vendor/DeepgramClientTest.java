package app.stageminute.analysis.vendor;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.stageminute.analysis.score.SentenceSpan;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The real Deepgram client against a local WireMock, never the real vendor (#19.1; 03 F-027, F-122; 05 F-080). The
 * rule that matters most: {@code mip_opt_out=true} is on every call, retries included.
 */
class DeepgramClientTest {

    private static final String KEY = "dg_test_not_a_real_key";
    private static final byte[] AUDIO = {1, 2, 3, 4, 5};
    private static final int TAKE_MS = 30_000;

    /** Two sentences, one filler; times in seconds as Deepgram writes them. */
    static final String ANSWER = """
            {"metadata":{"request_id":"r-1","duration":30.0,"model_info":{"abc":{"name":"general-nova-3","version":"2025-01-01"}}},
             "results":{"channels":[{"detected_language":"en","language_confidence":0.99,
               "alternatives":[{"transcript":"Hello there. Um I like it.","confidence":0.97,
                 "words":[
                   {"word":"hello","punctuated_word":"Hello","start":0.5,"end":0.9,"confidence":0.99},
                   {"word":"there","punctuated_word":"there.","start":0.9,"end":1.3,"confidence":0.98},
                   {"word":"um","punctuated_word":"Um","start":2.0,"end":2.3,"confidence":0.90},
                   {"word":"i","punctuated_word":"I","start":2.3,"end":2.4,"confidence":0.95},
                   {"word":"like","punctuated_word":"like","start":2.4,"end":2.7,"confidence":0.96},
                   {"word":"it","punctuated_word":"it.","start":2.7,"end":3.0,"confidence":0.97}],
                 "paragraphs":{"paragraphs":[{"sentences":[
                   {"text":"Hello there.","start":0.5,"end":1.3},
                   {"text":"Um I like it.","start":2.0,"end":3.0}]}]}}]}]}}
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

    private DeepgramClient client(VendorLimits limits) {
        UsageSink sink = new UsageSink() {
            @Override
            public void starting(Vendor vendor, long audioMs) {
                events.add("starting " + vendor.tag() + " " + audioMs);
            }

            @Override
            public void used(VendorUsage usage) {
                events.add("used audioMs=" + usage.audioMs());
            }

            @Override
            public void noUsageReported(Vendor vendor) {
                events.add("noUsage " + vendor.tag());
            }
        };
        return client(limits, sink);
    }

    private DeepgramClient client(VendorLimits limits, UsageSink sink) {
        return new DeepgramClient(
                new VendorEndpoint("http://localhost:" + wire.port(), KEY, "nova-3"),
                new VendorTransport(),
                limits,
                sink,
                new VendorGuard(Vendor.DEEPGRAM, limits, meters),
                meters);
    }

    private SpeechResult transcribe(VendorLimits limits) {
        return client(limits).transcribe(AUDIO, TAKE_MS, SttRequest.launch());
    }

    private void answerWith(int status, String body) {
        wire.stubFor(post(urlPathEqualTo("/v1/listen"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private List<LoggedRequest> calls() {
        return wire.findAll(postRequestedFor(urlPathEqualTo("/v1/listen")));
    }

    // ---- the request ----

    @Test
    void everyCallAsksForNova3WithTheOptOutAndFillerWordsAndSendsTheAudioFromMemory() {
        answerWith(200, ANSWER);

        transcribe(TestLimits.launch());

        wire.verify(postRequestedFor(urlPathEqualTo("/v1/listen"))
                .withQueryParam("model", equalTo("nova-3"))
                .withQueryParam("mip_opt_out", equalTo("true"))
                .withQueryParam("filler_words", equalTo("true"))
                .withQueryParam("punctuate", equalTo("true"))
                .withQueryParam("paragraphs", equalTo("true"))
                .withQueryParam("detect_language", equalTo("true"))
                .withHeader("Authorization", equalTo("Token " + KEY))
                .withHeader("Content-Type", equalTo("audio/mp4")));
        LoggedRequest sent = calls().get(0);
        assertThat(sent.getBody()).isEqualTo(AUDIO);
        assertThat(sent.getUrl()).doesNotContain(KEY);
    }

    @Test
    void aRequestThatDoesNotOptOutOrKeepFillersIsRefusedAndNothingIsSent() {
        answerWith(200, ANSWER);

        assertThatThrownBy(() -> client(TestLimits.launch()).transcribe(AUDIO, TAKE_MS, new SttRequest(false, true)))
                .isInstanceOf(VendorUnavailable.class)
                .hasMessage("stt_request_invalid");
        assertThatThrownBy(() -> client(TestLimits.launch()).transcribe(AUDIO, TAKE_MS, new SttRequest(true, false)))
                .isInstanceOf(VendorUnavailable.class)
                .hasMessage("stt_request_invalid");

        assertThat(wire.getAllServeEvents()).isEmpty();
        assertThat(events).isEmpty();
    }

    @Test
    void theOptOutIsOnTheRetryToo() {
        wire.stubFor(post(urlPathEqualTo("/v1/listen"))
                .inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("second"));
        wire.stubFor(post(urlPathEqualTo("/v1/listen"))
                .inScenario("flaky")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(200).withBody(ANSWER)));

        transcribe(TestLimits.launch());

        assertThat(calls()).hasSize(2);
        assertThat(calls())
                .allSatisfy(call ->
                        assertThat(call.queryParameter("mip_opt_out").values()).containsExactly("true"));
    }

    // ---- what the answer becomes ----

    @Test
    void wordsTimesConfidenceAndSentencesBecomeTheSpeechResultWithTheServerLength() {
        answerWith(200, ANSWER);

        SpeechResult result = transcribe(TestLimits.launch());

        var transcript = result.transcript();
        assertThat(transcript.durationMs()).isEqualTo(TAKE_MS); // the server-measured length (C-69), not Deepgram's
        assertThat(transcript.words()).hasSize(6);
        assertThat(transcript.words().get(1).text()).isEqualTo("there.");
        assertThat(transcript.words().get(1).startMs()).isEqualTo(900);
        assertThat(transcript.words().get(1).endMs()).isEqualTo(1300);
        assertThat(transcript.words().get(2).confidence()).isEqualTo(0.90);
        assertThat(transcript.sentences()).containsExactly(new SentenceSpan(0, 1), new SentenceSpan(2, 5));
        assertThat(result.englishHeard()).isTrue();
    }

    @Test
    void aLanguageOtherThanEnglishIsNotEnglishHeardAndAMissingSignalIs() {
        answerWith(200, ANSWER.replace("\"detected_language\":\"en\"", "\"detected_language\":\"hi\""));
        assertThat(transcribe(TestLimits.launch()).englishHeard()).isFalse();

        answerWith(200, ANSWER.replace("\"detected_language\":\"en\",", ""));
        assertThat(transcribe(TestLimits.launch()).englishHeard()).isTrue();
    }

    @Test
    void anAnswerWithNoWordsIsAnEmptyTranscriptNotAFailure() {
        answerWith(200, "{\"results\":{\"channels\":[{\"alternatives\":[{\"words\":[]}]}]}}");

        SpeechResult result = transcribe(TestLimits.launch());

        assertThat(result.transcript().words()).isEmpty();
        assertThat(result.transcript().sentences()).isEmpty();
    }

    @Test
    void wordsWithNoSentenceMarkingBecomeOneSentence() {
        answerWith(200, ANSWER.replace("\"paragraphs\"", "\"nope\""));

        SpeechResult result = transcribe(TestLimits.launch());

        assertThat(result.transcript().sentences()).containsExactly(new SentenceSpan(0, 5));
    }

    @Test
    void anEmptyOrOddBodyIsVendorUnavailableWithAFixedCodeAndNoVendorText() {
        for (String body : List.of(
                "",
                "not json at all SECRET-WORDS",
                "{}",
                "{\"results\":{\"channels\":[]}}",
                "{\"results\":{\"channels\":[{\"alternatives\":[{\"words\":[{\"word\":\"SECRET-WORDS\"}]}]}]}}",
                "{\"results\":{\"channels\":[{\"alternatives\":[{\"words\":["
                        + "{\"word\":\"a\",\"start\":2,\"end\":1,\"confidence\":0.5}]}]}]}}")) {
            answerWith(200, body);

            assertThatThrownBy(() -> transcribe(TestLimits.launch()))
                    .isInstanceOf(VendorUnavailable.class)
                    .hasMessage("stt_bad_answer")
                    .satisfies(e -> assertThat(e.getCause()).isNull());
        }
    }

    // ---- failures and the retry rule (F-122) ----

    @Test
    void anHttpErrorIsNeverRetried() {
        for (int status : new int[] {400, 401, 402, 429, 500, 502, 503}) {
            wire.resetRequests();
            answerWith(status, "{\"err_msg\":\"SECRET-VENDOR-TEXT\"}");

            assertThatThrownBy(() -> transcribe(TestLimits.launch()))
                    .isInstanceOf(VendorUnavailable.class)
                    .hasMessage(status >= 500 ? "stt_http_5xx" : "stt_http_4xx");
            assertThat(calls()).as("status %s", status).hasSize(1);
        }
    }

    @Test
    void aTimeoutIsNeverRetried() {
        wire.stubFor(post(urlPathEqualTo("/v1/listen"))
                .willReturn(aResponse().withStatus(200).withBody(ANSWER).withFixedDelay(3000)));

        assertThatThrownBy(() -> transcribe(TestLimits.launch().withBudgetMs(400)))
                .isInstanceOf(VendorUnavailable.class)
                .hasMessage("stt_timeout");
        assertThat(calls()).hasSize(1);
        assertThat(meters.counter("vendor.call", "vendor", "deepgram", "outcome", "timeout")
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void aNetworkErrorIsRetriedOnceAndThenFails() {
        wire.stubFor(
                post(urlPathEqualTo("/v1/listen")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> transcribe(TestLimits.launch()))
                .isInstanceOf(VendorUnavailable.class)
                .hasMessage("stt_network");
        assertThat(calls()).hasSize(2);
        assertThat(meters.counter("vendor.retry", "vendor", "deepgram", "outcome", "network")
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void aNetworkErrorIsNotRetriedWhenTheBudgetHasLessThanTheRetryMinimumLeft() {
        wire.stubFor(
                post(urlPathEqualTo("/v1/listen")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        VendorBudget.within(
                3000,
                () -> assertThatThrownBy(() -> transcribe(TestLimits.launch().withRetryMinRemainingMs(5000)))
                        .isInstanceOf(VendorUnavailable.class)
                        .hasMessage("stt_network"));

        assertThat(calls()).hasSize(1);
    }

    @Test
    void theCallGetsOnlyTheTimeLeftOfTheBudget() {
        wire.stubFor(post(urlPathEqualTo("/v1/listen"))
                .willReturn(aResponse().withStatus(200).withBody(ANSWER).withFixedDelay(1500)));

        long started = System.nanoTime();
        VendorBudget.within(
                400,
                () -> assertThatThrownBy(() -> transcribe(TestLimits.launch()))
                        .isInstanceOf(VendorUnavailable.class)
                        .hasMessage("stt_timeout"));

        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1400);
    }

    // ---- units, the key, the model name ----

    @Test
    void everyCallReportsStartAndUsageInUnitsAndNothingElse() {
        answerWith(200, ANSWER);

        transcribe(TestLimits.launch());

        assertThat(events).containsExactly("starting deepgram 30000", "used audioMs=30000");
    }

    @Test
    void aCallThatReportsNoUsageIsMarkedAndARetryStartsAgain() {
        wire.stubFor(
                post(urlPathEqualTo("/v1/listen")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> transcribe(TestLimits.launch())).isInstanceOf(VendorUnavailable.class);

        assertThat(events)
                .containsExactly(
                        "starting deepgram 30000", "noUsage deepgram", "starting deepgram 30000", "noUsage deepgram");
    }

    @Test
    void aSinkThatRefusesStopsTheCallBeforeAnythingIsSent() {
        UsageSink refusing = new UsageSink() {
            @Override
            public void starting(Vendor vendor, long audioMs) {
                throw new VendorUnavailable("spend_paused");
            }
        };

        assertThatThrownBy(() -> client(TestLimits.launch(), refusing).transcribe(AUDIO, TAKE_MS, SttRequest.launch()))
                .isInstanceOf(VendorUnavailable.class)
                .hasMessage("spend_paused");
        assertThat(wire.getAllServeEvents()).isEmpty();
    }

    @Test
    void theBilledAudioTimeIsWhatDeepgramSaysItProcessed() {
        answerWith(200, ANSWER.replace("\"duration\":30.0", "\"duration\":29.5"));

        transcribe(TestLimits.launch());

        assertThat(events).contains("used audioMs=29500");
    }

    @Test
    void anAnsweredModelThatIsNotTheOneWeAskedForIsCounted() {
        answerWith(200, ANSWER.replace("general-nova-3", "general-nova-2"));

        transcribe(TestLimits.launch());

        assertThat(meters.counter("vendor.model.mismatch", "vendor", "deepgram").count())
                .isEqualTo(1.0);
    }

    @Test
    void theAnsweredModelThatMatchesIsNotCounted() {
        answerWith(200, ANSWER);

        transcribe(TestLimits.launch());

        assertThat(meters.counter("vendor.model.mismatch", "vendor", "deepgram").count())
                .isZero();
    }

    @Test
    void theKeyNeverAppearsInAnErrorOrAToString() {
        wire.stubFor(post(urlPathEqualTo("/v1/listen")).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

        assertThatThrownBy(() -> transcribe(TestLimits.launch()))
                .isInstanceOf(VendorUnavailable.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain(KEY);
                    assertThat(e.getCause()).isNull();
                });
        assertThat(new VendorEndpoint("http://localhost:1", KEY, "nova-3").toString())
                .doesNotContain(KEY);
    }
}
