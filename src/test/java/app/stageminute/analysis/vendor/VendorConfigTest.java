package app.stageminute.analysis.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.stageminute.analysis.score.SentenceSpan;
import app.stageminute.analysis.score.Style;
import app.stageminute.analysis.score.Transcript;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The fakes fail closed (AD-128): with no setting there is no vendor and no take can be scored; only the exact dev
 * setting turns the fakes on; a prod or staging server refuses to start with it.
 */
class VendorConfigTest {

    private static final JudgeInput ANY = new JudgeInput(
            Style.PREP, "topic", new Transcript(List.of(), List.<SentenceSpan>of(), 30_000), List.of(), List.of());

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(VendorConfig.class);

    @Test
    void withNoSettingEveryVendorCallFails() {
        runner.run(context -> {
            assertThat(context.getBean(SpeechToText.class)).isNotInstanceOf(FakeSpeechToText.class);
            assertThat(context.getBean(Judge.class)).isNotInstanceOf(FakeJudge.class);
            assertThatThrownBy(() ->
                            context.getBean(SpeechToText.class).transcribe(new byte[] {1}, 30_000, SttRequest.launch()))
                    .isInstanceOf(VendorUnavailable.class);
            assertThatThrownBy(() -> context.getBean(Judge.class).judge(ANY)).isInstanceOf(VendorUnavailable.class);
        });
    }

    @Test
    void onlyTheExactWordTrueTurnsTheFakesOn() {
        runner.withPropertyValues(VendorConfig.FAKES + "=yes")
                .run(context ->
                        assertThat(context.getBean(SpeechToText.class)).isNotInstanceOf(FakeSpeechToText.class));
        runner.withPropertyValues(VendorConfig.FAKES + "=true", "spring.profiles.active=local")
                .run(context -> {
                    assertThat(context.getBean(SpeechToText.class)).isInstanceOf(FakeSpeechToText.class);
                    assertThat(context.getBean(Judge.class)).isInstanceOf(FakeJudge.class);
                });
    }

    @Test
    void aServerOutsideTheLocalOrTestProfileWillNotStartWithTheFakes() {
        runner.withPropertyValues(VendorConfig.FAKES + "=true")
                .run(context -> assertThat(context).hasFailed()); // no profile at all: a VM started without one
        for (String profile : List.of("prod", "staging", "lokal")) {
            runner.withPropertyValues(VendorConfig.FAKES + "=true", "spring.profiles.active=" + profile)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void theScenarioAndTheDelayComeFromTheDevSettings() {
        runner.withPropertyValues(
                        VendorConfig.FAKES + "=true",
                        VendorConfig.SCENARIO + "=vendor_failure",
                        "spring.profiles.active=test")
                .run(context -> assertThatThrownBy(() -> context.getBean(SpeechToText.class)
                                .transcribe(new byte[] {1}, 30_000, SttRequest.launch()))
                        .isInstanceOf(VendorUnavailable.class));
        assertThat(FakeScenario.of("low-audio")).isEqualTo(FakeScenario.LOW_AUDIO);
        assertThat(FakeScenario.of("nonsense")).isEqualTo(FakeScenario.OK);
    }
}
