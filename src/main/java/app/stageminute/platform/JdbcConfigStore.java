package app.stageminute.platform;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** {@link ConfigStore} over the {@code app_settings} and {@code feature_flags} tables (03-DATABASE.md). */
@Component
class JdbcConfigStore implements ConfigStore {

    private final JdbcClient jdbc;

    JdbcConfigStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> settingValue(String key) {
        return jdbc.sql("SELECT value::text FROM app_settings WHERE key = :key")
                .param("key", key)
                .query(String.class)
                .optional();
    }

    @Override
    public Optional<Boolean> flagEnabled(String key) {
        return jdbc.sql("SELECT enabled FROM feature_flags WHERE key = :key")
                .param("key", key)
                .query(Boolean.class)
                .optional();
    }
}
