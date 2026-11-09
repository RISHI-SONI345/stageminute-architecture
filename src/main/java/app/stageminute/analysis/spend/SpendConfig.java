package app.stageminute.analysis.spend;

import app.stageminute.platform.SettingKey;
import app.stageminute.platform.SettingList;
import app.stageminute.platform.Settings;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the spend meter for the server (#19.2, AD-152): every number read by name from the settings on each use, so a
 * change in {@code app_settings} takes effect on the next call with no restart. The one bean is both the {@code
 * UsageSink} the real vendor clients report to (picked up by {@code VendorConfig}) and the {@code SpendGate} that
 * {@code practice} asks.
 */
@Configuration(proxyBeanMethods = false)
class SpendConfig {

    private static final long MICROS_PER_USD = 1_000_000L;

    @Bean
    SpendMeter spendMeter(SpendStore store, Settings settings, Clock clock, MeterRegistry meters) {
        return new SpendMeter(store, () -> limits(settings), clock, meters);
    }

    /** The meter's numbers from the settings: a missing row is the launch default, and a zero stops everything. */
    static SpendLimits limits(Settings settings) {
        return new SpendLimits(
                new ThrottlePolicy.Levels(
                        settings.get(SettingKey.AI_DAILY_BUDGET_USD) * MICROS_PER_USD,
                        settings.get(SettingKey.AI_ALERT_PERCENT),
                        settings.get(SettingKey.AI_FREE_THROTTLE_PERCENT),
                        settings.get(SettingKey.AI_PRO_THROTTLE_PERCENT),
                        settings.get(SettingKey.PRO_THROTTLED_CEILING)),
                settings.get(SettingKey.AI_CALL_STOP_PERCENT),
                settings.get(SettingKey.AI_EXPOSURE_WINDOW_SECONDS),
                new SpendPrices(
                        settings.get(SettingKey.DEEPGRAM_MICROS_PER_AUDIO_MINUTE),
                        settings.get(SettingKey.GEMINI_INPUT_MICROS_PER_MILLION_TOKENS),
                        settings.get(SettingKey.GEMINI_OUTPUT_MICROS_PER_MILLION_TOKENS),
                        settings.get(SettingKey.GEMINI_WORST_CASE_INPUT_TOKENS),
                        settings.get(SettingKey.GEMINI_WORST_CASE_OUTPUT_TOKENS)),
                ThrottleOverride.of(settings.list(SettingList.AI_THROTTLE_OVERRIDE)),
                settings.get(SettingKey.AI_RETRY_ALERT_PERCENT),
                settings.get(SettingKey.AI_RETRY_ALERT_MIN_CALLS));
    }
}
