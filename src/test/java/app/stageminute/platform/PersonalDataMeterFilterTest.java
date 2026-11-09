package app.stageminute.platform;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** Metrics carry counts, never a user id or an email in a label (AD-23). */
class PersonalDataMeterFilterTest {

    private SimpleMeterRegistry registry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new PersonalDataMeterFilter());
        return registry;
    }

    @Test
    void keepsAnOrdinaryLabel() {
        SimpleMeterRegistry registry = registry();
        registry.counter("stageminute.takes", "outcome", "ok").increment();

        assertThat(registry.getMeters()).hasSize(1);
    }

    @Test
    void dropsLabelsNamedLikeAPerson() {
        SimpleMeterRegistry registry = registry();
        for (String key : new String[] {"user_id", "userId", "User", "email", "request_id", "device_id", "ip"}) {
            registry.counter("stageminute.bad", key, "value-1234").increment();
        }

        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void dropsALabelValueThatLooksLikeAnEmail() {
        SimpleMeterRegistry registry = registry();
        registry.counter("stageminute.bad", "who", "someone@example.com").increment();

        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void keepsMicrometersOwnNameLabelAndDropsMoreIdentityKeys() {
        SimpleMeterRegistry registry = registry();
        registry.counter("executor.completed", "name", "taskExecutor").increment();
        for (String key : new String[] {"sub", "token", "session", "client_ip"}) {
            registry.counter("stageminute.bad", key, "value-1234").increment();
        }

        assertThat(registry.getMeters()).hasSize(1);
    }
}
