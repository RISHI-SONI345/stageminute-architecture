package app.stageminute.analysis.vendor;

import app.stageminute.platform.SettingKey;
import java.util.function.ToIntFunction;

/**
 * {@link VendorLimits} read by name from the server settings (root CLAUDE.md "Numbers"). The reader is the settings
 * store in the server and the launch defaults where there is no store (a test of the wiring alone). The budget is read
 * on every call; the breaker and bulkhead values are read when the server starts.
 */
final class SettingsVendorLimits implements VendorLimits {

    private final ToIntFunction<SettingKey> reader;

    SettingsVendorLimits(ToIntFunction<SettingKey> reader) {
        this.reader = reader;
    }

    @Override
    public int budgetMs() {
        return reader.applyAsInt(SettingKey.ANALYSIS_BUDGET_MS);
    }

    @Override
    public int retryMinRemainingMs() {
        return reader.applyAsInt(SettingKey.VENDOR_RETRY_MIN_REMAINING_MS);
    }

    @Override
    public int breakerFailurePercent() {
        return reader.applyAsInt(SettingKey.VENDOR_BREAKER_FAILURE_PERCENT);
    }

    @Override
    public int breakerWindowCalls() {
        return reader.applyAsInt(SettingKey.VENDOR_BREAKER_WINDOW_CALLS);
    }

    @Override
    public int breakerMinCalls() {
        return reader.applyAsInt(SettingKey.VENDOR_BREAKER_MIN_CALLS);
    }

    @Override
    public int breakerOpenSeconds() {
        return reader.applyAsInt(SettingKey.VENDOR_BREAKER_OPEN_SECONDS);
    }

    @Override
    public int breakerHalfOpenCalls() {
        return reader.applyAsInt(SettingKey.VENDOR_BREAKER_HALF_OPEN_CALLS);
    }

    @Override
    public int bulkheadMaxCalls() {
        return reader.applyAsInt(SettingKey.VENDOR_BULKHEAD_MAX_CALLS);
    }

    @Override
    public int geminiThinkingBudgetTokens() {
        return reader.applyAsInt(SettingKey.GEMINI_THINKING_BUDGET_TOKENS);
    }
}
