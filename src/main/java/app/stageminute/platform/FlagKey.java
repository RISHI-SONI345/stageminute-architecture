package app.stageminute.platform;

import java.util.Locale;

/**
 * Feature flags. Every flag is off until a row says otherwise (fail closed): no exit ad, no soft sheet,
 * no strong privacy copy, no carried fix.
 */
public enum FlagKey {
    EXIT_AD_ENABLED,
    SOFT_SHEET_ENABLED,
    CARRY_FIX_ENABLED,
    PRIVACY_STRONG_COPY;

    /** The row key in {@code feature_flags}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
