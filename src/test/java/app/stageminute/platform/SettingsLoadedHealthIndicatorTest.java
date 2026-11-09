package app.stageminute.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class SettingsLoadedHealthIndicatorTest {

    private final SettingsAndFlagsTest.FakeStore store = new SettingsAndFlagsTest.FakeStore();
    private final SettingsLoadedHealthIndicator indicator = new SettingsLoadedHealthIndicator(store);

    @Test
    void upWhenTheSettingsStoreCanBeRead() {
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void downWhenTheSettingsStoreCannotBeRead() {
        store.broken = true;

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
