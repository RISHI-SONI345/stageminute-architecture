package app.stageminute.platform;

import java.util.Optional;

/**
 * Where {@link Settings} and {@link Flags} read their stored values. The one real store is the database
 * ({@code app_settings}, {@code feature_flags}); tests use a map. Implementations may throw when the store
 * cannot be read; the readers above treat that as "not set" and fall back to the safe value.
 */
public interface ConfigStore {

    /** The stored JSON text of a setting, or empty when no row exists. */
    Optional<String> settingValue(String key);

    /** The {@code enabled} column of a flag row, or empty when no row exists. */
    Optional<Boolean> flagEnabled(String key);
}
