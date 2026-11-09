package app.stageminute.analysis.vendor;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Which adapter the server gets (AD-128, AD-151): fakes only when the dev setting says so, real clients only when the
 * keys exist, nothing otherwise, and a staging or prod server that lacks a key does not start (and never prints it).
 */
class VendorWiringTest {

    private static final String DG_KEY = "dg_secret_value_123";
    private static final String GM_KEY = "gm_secret_value_456";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(VendorConfig.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    private ApplicationContextRunner withKeys() {
        return runner.withPropertyValues(
                VendorConfig.DEEPGRAM_KEY + "=" + DG_KEY, VendorConfig.GEMINI_KEY + "=" + GM_KEY);
    }

    @Test
    void withBothKeysAndNoFakesTheRealClientsAreUsed() {
        withKeys().run(context -> {
            assertThat(context.getBean(SpeechToText.class)).isInstanceOf(DeepgramClient.class);
            assertThat(context.getBean(Judge.class)).isInstanceOf(GeminiJudge.class);
            assertThat(context.getBean(Judge.class).versions().model()).isEqualTo("gemini-3.8-flash");
            assertThat(context.getBean(Judge.class).versions().prompt()).doesNotContain("fake", "none");
        });
    }

    @Test
    void theFakesWinWhenTheDevSettingIsOnEvenWithKeys() {
        withKeys()
                .withPropertyValues(VendorConfig.FAKES + "=true", "spring.profiles.active=local")
                .run(context -> {
                    assertThat(context.getBean(SpeechToText.class)).isInstanceOf(FakeSpeechToText.class);
                    assertThat(context.getBean(Judge.class)).isInstanceOf(FakeJudge.class);
                });
    }

    @Test
    void aKeyForOneVendorOnlyBuildsThatOneAndTheOtherStaysClosed() {
        runner.withPropertyValues(VendorConfig.DEEPGRAM_KEY + "=" + DG_KEY).run(context -> {
            assertThat(context.getBean(SpeechToText.class)).isInstanceOf(DeepgramClient.class);
            assertThat(context.getBean(Judge.class)).isNotInstanceOf(GeminiJudge.class);
        });
        runner.withPropertyValues(VendorConfig.GEMINI_KEY + "=" + GM_KEY).run(context -> {
            assertThat(context.getBean(Judge.class)).isInstanceOf(GeminiJudge.class);
            assertThat(context.getBean(SpeechToText.class)).isNotInstanceOf(DeepgramClient.class);
        });
    }

    @Test
    void aBlankKeyIsNoKey() {
        runner.withPropertyValues(VendorConfig.DEEPGRAM_KEY + "=   ", VendorConfig.GEMINI_KEY + "=")
                .run(context -> {
                    assertThat(context.getBean(SpeechToText.class)).isNotInstanceOf(DeepgramClient.class);
                    assertThat(context.getBean(Judge.class)).isNotInstanceOf(GeminiJudge.class);
                });
    }

    @Test
    void stagingAndProdWillNotStartWithoutBothKeysAndNeverPrintAKey() {
        for (String profile : List.of("staging", "prod")) {
            runner.withPropertyValues("spring.profiles.active=" + profile).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("DEEPGRAM_API_KEY is not set; the server will not start without it");
            });
            runner.withPropertyValues("spring.profiles.active=" + profile, VendorConfig.DEEPGRAM_KEY + "=" + DG_KEY)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasRootCauseMessage("GEMINI_API_KEY is not set; the server will not start without it");
                        assertThat(String.valueOf(context.getStartupFailure().getMessage()))
                                .doesNotContain(DG_KEY);
                    });
            runner.withPropertyValues(
                            "spring.profiles.active=" + profile,
                            VendorConfig.DEEPGRAM_KEY + "=" + DG_KEY,
                            VendorConfig.GEMINI_KEY + "=" + GM_KEY)
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Test
    void aVendorAddressThatIsNotHttpsStopsTheServerAndNamesTheSettingNotTheAddress() {
        withKeys()
                .withPropertyValues(VendorConfig.DEEPGRAM_URL + "=http://deepgram.example.com")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("DEEPGRAM_BASE_URL must be an https address");
                });
        withKeys()
                .withPropertyValues(VendorConfig.GEMINI_URL + "=ftp://x")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theGuardsExposeTheBreakerStateOfBothVendors() {
        withKeys().run(context -> {
            VendorGuards guards = context.getBean(VendorGuards.class);
            assertThat(guards.of(Vendor.DEEPGRAM).state()).isEqualTo(VendorGuard.State.CLOSED);
            assertThat(guards.of(Vendor.GEMINI).state()).isEqualTo(VendorGuard.State.CLOSED);
            assertThat(guards.all()).hasSize(2);
        });
    }

    @Test
    void aUsageSinkBeanIsPluggedIntoTheRealClientsWithoutChangingThem() {
        WireMockServer wire = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wire.start();
        try {
            wire.stubFor(post(urlPathEqualTo("/v1/listen"))
                    .willReturn(aResponse().withStatus(200).withBody(DeepgramClientTest.ANSWER)));
            List<String> seen = new ArrayList<>();
            UsageSink sink = new UsageSink() {
                @Override
                public void starting(Vendor vendor, long audioMs) {
                    seen.add("starting " + vendor.tag());
                }

                @Override
                public void used(VendorUsage usage) {
                    seen.add("used " + usage.vendor().tag() + " " + usage.audioMs());
                }
            };

            withKeys()
                    .withPropertyValues(VendorConfig.DEEPGRAM_URL + "=http://localhost:" + wire.port())
                    .withBean(UsageSink.class, () -> sink)
                    .run(context -> context.getBean(SpeechToText.class)
                            .transcribe(new byte[] {1, 2}, 30_000, SttRequest.launch()));

            assertThat(seen).containsExactly("starting deepgram", "used deepgram 30000");
        } finally {
            wire.stop();
        }
    }
}
