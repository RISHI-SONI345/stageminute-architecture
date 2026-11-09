package app.stageminute.analysis.vendor;

import app.stageminute.platform.Settings;
import app.stageminute.platform.TrustSettings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Bridge for the trust harness (tests/trust, AD-153). The real clients and their wiring are package-private; this class
 * builds them through the server's own {@code VendorConfig}, so the harness measures the same Deepgram and Gemini
 * clients (with {@code mip_opt_out=true}, the F-122 time budget, retries, breaker and bulkhead) that production runs.
 * It is compiled in the harness's own source set, so the server never ships it. Keys come from the environment
 * variables {@code DEEPGRAM_API_KEY} and {@code GEMINI_API_KEY}; a missing one is named, a value is never printed.
 */
public final class TrustVendors {

    /** The two vendors, and whether they are real. */
    public record Vendors(SpeechToText speech, Judge judge, boolean real) {}

    private TrustVendors() {}

    /** The deterministic fakes: nothing leaves this machine. */
    public static Vendors fakes() {
        return new Vendors(new FakeSpeechToText(FakeScenario.OK, 0), new FakeJudge(), false);
    }

    /**
     * The real clients. Each call's use is reported to {@code sink}.
     *
     * @param env the process environment (or a test's copy of it)
     * @throws IllegalStateException naming the variable when a key is missing
     */
    public static Vendors real(Map<String, String> env, UsageSink sink) {
        return real(env, sink, TrustSettings.launchDefaults());
    }

    /** The real clients with these settings (a trial of one setting, such as the Gemini thinking budget). */
    public static Vendors real(Map<String, String> env, UsageSink sink, Settings settings) {
        String deepgram = require(env, "DEEPGRAM_API_KEY");
        String gemini = require(env, "GEMINI_API_KEY");
        Map<String, Object> properties = new HashMap<>();
        properties.put(VendorConfig.DEEPGRAM_KEY, deepgram);
        properties.put(VendorConfig.GEMINI_KEY, gemini);
        optional(env, "DEEPGRAM_BASE_URL", VendorConfig.DEEPGRAM_URL, properties);
        optional(env, "DEEPGRAM_MODEL", VendorConfig.DEEPGRAM_MODEL, properties);
        optional(env, "GEMINI_BASE_URL", VendorConfig.GEMINI_URL, properties);
        optional(env, "GEMINI_MODEL", VendorConfig.GEMINI_MODEL, properties);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("trust-harness", properties));

        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("settings", settings);
        beans.registerSingleton("usageSink", sink);
        VendorConfig config = new VendorConfig();
        VendorLimits limits = config.vendorLimits(beans.getBeanProvider(Settings.class));
        VendorGuards guards = config.vendorGuards(limits, beans.getBeanProvider(MeterRegistry.class));
        return new Vendors(
                config.speechToText(
                        environment,
                        limits,
                        guards,
                        beans.getBeanProvider(UsageSink.class),
                        beans.getBeanProvider(MeterRegistry.class)),
                config.judge(
                        environment,
                        limits,
                        guards,
                        beans.getBeanProvider(UsageSink.class),
                        beans.getBeanProvider(MeterRegistry.class)),
                true);
    }

    private static String require(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set; a real run needs it (the value is never printed)");
        }
        return value.trim();
    }

    private static void optional(Map<String, String> env, String name, String property, Map<String, Object> into) {
        String value = env.get(name);
        if (value != null && !value.isBlank()) {
            into.put(property, value.trim());
        }
    }
}
