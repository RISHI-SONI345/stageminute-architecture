package app.stageminute.platform;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the scheduled jobs ({@code @Scheduled} methods in each module's {@code jobs/}). Tests switch it off
 * ({@code stageminute.jobs.enabled=false}) and call a job's method themselves, so a job never runs in the
 * middle of a test that moved the clock.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "stageminute.jobs.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {}
