package app.stageminute.platform;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Part of the readiness group (gap G3): the server is ready only when the settings table can be read. It
 * answers up or down and nothing else, so no database detail reaches the public health page.
 */
@Component
class SettingsLoadedHealthIndicator implements HealthIndicator {

    private final ConfigStore store;

    SettingsLoadedHealthIndicator(ConfigStore store) {
        this.store = store;
    }

    @Override
    public Health health() {
        try {
            store.settingValue(SettingKey.FREE_SESSIONS_PER_DAY.key());
            return Health.up().build();
        } catch (RuntimeException e) {
            return Health.down().build();
        }
    }
}
