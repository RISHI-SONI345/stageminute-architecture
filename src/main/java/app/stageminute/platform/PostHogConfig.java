package app.stageminute.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the PostHog adapter. With no personal API key or no project id there is no vendor to call, so the adapter
 * answers {@link DeleteOutcome#NOT_CONFIGURED} and the event is done (AD-149): PostHog has no account yet (ticket #7),
 * so nobody can be in it. The address must be https (plain http only to this machine, for tests).
 */
@Configuration(proxyBeanMethods = false)
class PostHogConfig {

    @Bean
    PostHogPersons postHogPersons(
            @Value("${stageminute.posthog.host:}") String host,
            @Value("${stageminute.posthog.project-id:}") String projectId,
            @Value("${stageminute.posthog.personal-api-key:}") String key) {
        if (key.isBlank() || projectId.isBlank() || host.isBlank()) {
            return distinctId -> DeleteOutcome.NOT_CONFIGURED;
        }
        return new HttpPostHogPersons(
                VendorHttp.requireSafeBase("POSTHOG_API_HOST", host), projectId.trim(), key.trim());
    }
}
