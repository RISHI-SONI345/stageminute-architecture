package app.stageminute.trust.run;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.stageminute.analysis.internal.AnalysisSettings;
import app.stageminute.analysis.internal.Pipeline;
import app.stageminute.analysis.score.Style;
import app.stageminute.analysis.spend.SpendPrices;
import app.stageminute.analysis.vendor.TrustVendors;
import app.stageminute.analysis.vendor.UsageSink;
import app.stageminute.platform.TrustSettings;
import app.stageminute.reports.FixTexts;
import app.stageminute.trust.labelset.SyntheticSet;
import app.stageminute.trust.model.Observation;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The harness's REAL path (the same Deepgram and Gemini clients as production, built through the server's own wiring)
 * pointed at a local WireMock and never at a vendor: every Deepgram request carries {@code mip_opt_out=true}, the run
 * is timed and costed from the usage the vendors report, and the versions and the answered model are collected.
 */
class RealClientsOnWireMockTest {

    private static final String GEMINI_PATH = "/v1beta/models/gemini-3.8-flash:generateContent";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final WireMockServer WIRE =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIRE.start();
    }

    @AfterAll
    static void stop() {
        WIRE.stop();
    }

    @BeforeEach
    void reset() {
        WIRE.resetAll();
    }

    private static Map<String, String> env() {
        return Map.of(
                "DEEPGRAM_API_KEY",
                "dg-test-key",
                "GEMINI_API_KEY",
                "gm-test-key",
                "DEEPGRAM_BASE_URL",
                "http://localhost:" + WIRE.port(),
                "GEMINI_BASE_URL",
                "http://localhost:" + WIRE.port());
    }

    /** 120 words, 0.4 s each, a sentence every 12 words: a take that clears the no-score gate. */
    private static String deepgramAnswer() {
        StringBuilder words = new StringBuilder();
        StringBuilder sentences = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            double start = 1.0 + i * 0.4;
            words.append(i == 0 ? "" : ",")
                    .append("{\"word\":\"alpha\",\"punctuated_word\":\"alpha")
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

    private static String geminiAnswer() {
        String labels = "{\"steps\":[\"strong\",\"strong\",\"weak\",\"strong\"],\"step_sentences\":[0,1,2,9],"
                + "\"relevance\":4,\"clarity\":3,\"conciseness\":3,\"accuracy\":4,\"fillers\":[],\"run_on\":false,"
                + "\"verdict\":\"Clear point and a clean closing.\",\"try_line\":\"Give your example a name.\","
                + "\"rewrite\":null,\"strongest_sentence\":1,\"strongest_why\":\"You named it plainly.\","
                + "\"language_items\":[]}";
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + JSON.writeValueAsString(labels)
                + "}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":1000,"
                + "\"candidatesTokenCount\":150},\"modelVersion\":\"gemini-3.8-flash-001\"}";
    }

    private static PipelineRunner runner(UsageMeter meter) throws java.io.IOException {
        TrustVendors.Vendors vendors = TrustVendors.real(env(), meter);
        FixTexts texts = FixTexts.parse(
                new String(
                        RealClientsOnWireMockTest.class
                                .getResourceAsStream("/content-fixes/en.json")
                                .readAllBytes(),
                        StandardCharsets.UTF_8),
                JSON);
        return PipelineRunner.of(
                new Pipeline(
                        vendors.speech(), vendors.judge(), new AnalysisSettings(TrustSettings.launchDefaults()), texts),
                meter);
    }

    @Test
    void everyDeepgramRequestCarriesMipOptOutTrueAndTheRunIsCostedAndStamped() throws Exception {
        WIRE.stubFor(post(urlPathEqualTo("/v1/listen"))
                .willReturn(aResponse().withStatus(200).withBody(deepgramAnswer())));
        WIRE.stubFor(post(urlPathEqualTo(GEMINI_PATH))
                .willReturn(aResponse().withStatus(200).withBody(geminiAnswer())));
        SpendPrices prices = new SpendPrices(4_300, 300_000, 2_500_000, 8_000, 8_192);
        UsageMeter meter = new UsageMeter(prices);
        PipelineRunner runner = runner(meter);

        Observation run = runner.run(SyntheticSet.m4a(55_000), Style.PREP, "Tell us about something.", 60_000);

        assertThat(WIRE.findAll(postRequestedFor(urlPathEqualTo("/v1/listen"))))
                .isNotEmpty()
                .allSatisfy(request -> assertThat(
                                request.queryParameter("mip_opt_out").values())
                        .containsExactly("true"));
        WIRE.verify(postRequestedFor(urlPathEqualTo("/v1/listen")).withQueryParam("mip_opt_out", equalTo("true")));
        assertThat(run.scored()).isTrue();
        assertThat(run.steps()).hasSize(4);
        assertThat(run.total()).isBetween(1, 100);
        assertThat(run.measuredDurationMs()).isEqualTo(55_000);
        assertThat(run.waitMs()).isNotNegative();
        assertThat(run.costMicros()).isPositive();
        assertThat(run.facts()).isPresent();

        var meta = runner.versions("2026-11-02T00:00:00Z", "real");
        assertThat(meta.promptVersion()).isNotBlank().isNotEqualTo("fake-prompt-0");
        assertThat(meta.schemaVersion()).isNotBlank().isNotEqualTo("fake-schema-0");
        assertThat(meta.pinnedModel()).isEqualTo("gemini-3.8-flash");
        assertThat(meta.answeredModels()).containsExactly("gemini-3.8-flash-001");
    }

    @Test
    void aMissingKeyIsNamedAndNoValueIsEverInTheMessage() {
        assertThatThrownBy(() -> TrustVendors.real(Map.of("DEEPGRAM_API_KEY", "dg-secret"), UsageSink.NONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GEMINI_API_KEY")
                .hasMessageNotContaining("dg-secret");
        assertThatThrownBy(() -> TrustVendors.real(Map.of(), UsageSink.NONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEEPGRAM_API_KEY");
    }
}
