package app.stageminute.analysis.vendor;

import app.stageminute.platform.SettingKey;
import app.stageminute.platform.Settings;
import app.stageminute.platform.VendorHttp;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Picks the speech-to-text and judge adapters (fail closed, AD-128, AD-151).
 *
 * <ol>
 *   <li>The fakes run only when the dev setting {@code stageminute.analysis.fake-vendors=true} is on, and the server
 *       refuses to start with that setting under any profile other than {@code local} or {@code test} (including no
 *       profile), so a real person can never get a fake score.
 *   <li>Otherwise a real client is built for each vendor whose key is set ({@code DEEPGRAM_API_KEY}, {@code
 *       GEMINI_API_KEY}).
 *   <li>Otherwise there is no vendor: every call fails and {@code POST /v1/analyze} answers {@code 502
 *       analysis_unavailable} with the session given back. Under {@code staging} and {@code prod} a missing key stops
 *       the server at start instead, naming the variable and never a value.
 * </ol>
 *
 * <p>Dev settings: {@code stageminute.analysis.fake-scenario} ({@code ok}, {@code short_take}, {@code low_audio},
 * {@code not_english}, {@code no_speech}, {@code vendor_failure}) and {@code stageminute.analysis.fake-delay-ms} (to see
 * the app's "Taking a bit longer" state). A {@link UsageSink} bean, if one exists, receives what each real call used
 * (the spend meter of #19.2); without one nothing is recorded.
 */
@Configuration(proxyBeanMethods = false)
class VendorConfig {

    static final String FAKES = "stageminute.analysis.fake-vendors";
    static final String SCENARIO = "stageminute.analysis.fake-scenario";
    static final String DELAY = "stageminute.analysis.fake-delay-ms";
    static final String DEEPGRAM_KEY = "stageminute.vendors.deepgram.api-key";
    static final String DEEPGRAM_URL = "stageminute.vendors.deepgram.base-url";
    static final String DEEPGRAM_MODEL = "stageminute.vendors.deepgram.model";
    static final String GEMINI_KEY = "stageminute.vendors.gemini.api-key";
    static final String GEMINI_URL = "stageminute.vendors.gemini.base-url";
    static final String GEMINI_MODEL = "stageminute.vendors.gemini.model";

    private static final String DEEPGRAM_DEFAULT_URL = "https://api.deepgram.com";
    private static final String DEEPGRAM_DEFAULT_MODEL = "nova-3";
    private static final String GEMINI_DEFAULT_URL = "https://generativelanguage.googleapis.com";
    private static final String GEMINI_DEFAULT_MODEL = "gemini-3.8-flash";

    @Bean
    VendorLimits vendorLimits(ObjectProvider<Settings> settings) {
        Settings store = settings.getIfAvailable();
        return new SettingsVendorLimits(store == null ? SettingKey::launchDefault : store::get);
    }

    @Bean
    VendorGuards vendorGuards(VendorLimits limits, ObjectProvider<MeterRegistry> meters) {
        MeterRegistry registry = meters.getIfAvailable(SimpleMeterRegistry::new);
        return new VendorGuards(
                new VendorGuard(Vendor.DEEPGRAM, limits, registry), new VendorGuard(Vendor.GEMINI, limits, registry));
    }

    @Bean
    SpeechToText speechToText(
            Environment env,
            VendorLimits limits,
            VendorGuards guards,
            ObjectProvider<UsageSink> sinks,
            ObjectProvider<MeterRegistry> meters) {
        if (fakesOn(env)) {
            return new FakeSpeechToText(
                    FakeScenario.of(env.getProperty(SCENARIO, "ok")), env.getProperty(DELAY, Long.class, 0L));
        }
        String key = env.getProperty(DEEPGRAM_KEY, "").trim();
        if (key.isEmpty()) {
            requireKeyOutsideDev(env, "DEEPGRAM_API_KEY");
            return (audio, durationMs, request) -> {
                throw new VendorUnavailable("no_speech_to_text");
            };
        }
        VendorEndpoint endpoint = new VendorEndpoint(
                VendorHttp.requireSafeBase("DEEPGRAM_BASE_URL", env.getProperty(DEEPGRAM_URL, DEEPGRAM_DEFAULT_URL)),
                key,
                env.getProperty(DEEPGRAM_MODEL, DEEPGRAM_DEFAULT_MODEL));
        return new DeepgramClient(
                endpoint,
                new VendorTransport(),
                limits,
                sinks.getIfAvailable(() -> UsageSink.NONE),
                guards.of(Vendor.DEEPGRAM),
                meters.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean
    Judge judge(
            Environment env,
            VendorLimits limits,
            VendorGuards guards,
            ObjectProvider<UsageSink> sinks,
            ObjectProvider<MeterRegistry> meters) {
        if (fakesOn(env)) {
            return new FakeJudge();
        }
        String key = env.getProperty(GEMINI_KEY, "").trim();
        if (key.isEmpty()) {
            requireKeyOutsideDev(env, "GEMINI_API_KEY");
            return new ClosedJudge();
        }
        VendorEndpoint endpoint = new VendorEndpoint(
                VendorHttp.requireSafeBase("GEMINI_BASE_URL", env.getProperty(GEMINI_URL, GEMINI_DEFAULT_URL)),
                key,
                env.getProperty(GEMINI_MODEL, GEMINI_DEFAULT_MODEL));
        return new GeminiJudge(
                endpoint,
                PromptFiles.fromClasspath(),
                new VendorTransport(),
                limits,
                sinks.getIfAvailable(() -> UsageSink.NONE),
                guards.of(Vendor.GEMINI),
                meters.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * Only the exact word {@code true} turns the fakes on, and only under the {@code local} or {@code test} profile:
     * an allow-list, so a server started with no profile or a mistyped one can never give a fake score.
     */
    private static boolean fakesOn(Environment env) {
        boolean on = "true".equals(env.getProperty(FAKES, "").trim().toLowerCase(Locale.ROOT));
        if (on && !env.acceptsProfiles(Profiles.of("local", "test"))) {
            throw new IllegalStateException(
                    FAKES + " is on outside the local or test profile; the server will not start");
        }
        return on;
    }

    /** A staging or prod server with no key does not start: a person must never be told "unavailable" for ever. */
    private static void requireKeyOutsideDev(Environment env, String variable) {
        if (env.acceptsProfiles(Profiles.of("staging", "prod"))) {
            throw new IllegalStateException(variable + " is not set; the server will not start without it");
        }
    }

    /** No judge configured: every call fails, so no take is ever scored without a real one. */
    private static final class ClosedJudge implements Judge {

        private static final JudgeVersions NONE = new JudgeVersions("none", "none", "none");

        @Override
        public JudgeLabels judge(JudgeInput input) {
            throw new VendorUnavailable("no_judge");
        }

        @Override
        public JudgeVersions versions() {
            return NONE;
        }
    }
}
