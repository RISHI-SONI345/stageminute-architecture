package app.stageminute.platform;

import java.util.Map;
import java.util.Optional;

/**
 * Bridge for the trust harness (tests/trust, AD-153). It sits in the {@code platform} package only because the
 * {@link Settings} constructor is package-private; it is compiled in the harness's own source set, so the server never
 * ships it. It gives the settings with every value at its launch default and no database.
 */
public final class TrustSettings {

    private TrustSettings() {}

    public static Settings launchDefaults() {
        return withValues(Map.of());
    }

    /**
     * The launch defaults, except the named settings, which take the given whole-number text (a trial of one setting,
     * such as the Gemini thinking budget, #19.3). Nothing is written anywhere.
     */
    public static Settings withValues(Map<SettingKey, Integer> values) {
        return new Settings(new ConfigStore() {
            @Override
            public Optional<String> settingValue(String key) {
                return values.entrySet().stream()
                        .filter(entry -> entry.getKey().key().equals(key))
                        .map(entry -> String.valueOf(entry.getValue()))
                        .findFirst();
            }

            @Override
            public Optional<Boolean> flagEnabled(String key) {
                return Optional.empty();
            }
        });
    }
}
