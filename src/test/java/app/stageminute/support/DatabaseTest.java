package app.stageminute.support;

import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Base class for tests that need the real database (tag {@code db}). The whole application starts, Flyway
 * wipes the test database and runs every migration, so each run begins from an empty schema.
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// The webhook secret is a plain property so a test class can switch the webhook off by setting it empty.
@TestPropertySource(properties = "stageminute.clerk.webhook-secret=" + FakeClerk.WEBHOOK_SECRET)
@Import(DatabaseTest.FreshSchema.class)
public abstract class DatabaseTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::url);
        registry.add("spring.datasource.username", TestDatabase::user);
        registry.add("spring.datasource.password", TestDatabase::password);
        registry.add("spring.flyway.clean-disabled", () -> "false");
        // Each differently configured test app is cached with its own pool; small pools keep a whole run
        // far below the database's connection limit.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        // Every new test app wipes and rebuilds the shared schema, so a cached app may come back to a database whose
        // enum types were created again. A server-side prepared statement would then fail with "cache lookup failed
        // for type"; with threshold 0 the driver never keeps one, and the order of test classes cannot matter.
        registry.add("spring.datasource.hikari.data-source-properties.prepareThreshold", () -> "0");
        // Jobs run only when a test calls them, never on a timer in the middle of a test that moved the clock.
        registry.add("stageminute.jobs.enabled", () -> "false");
        // Tests never send anything to Sentry, even when server/.env holds a real address.
        registry.add("sentry.dsn", () -> "");
        // A fixed test pepper, so no test depends on what server/.env holds.
        registry.add("stageminute.hash-pepper", () -> "test-pepper-test-pepper-test-pepper-0000");
        // Sign-in talks to the fake Clerk (a local key server), never to the real one.
        registry.add("stageminute.clerk.issuer", () -> FakeClerk.ISSUER);
        registry.add("stageminute.clerk.jwks-url", FakeClerk::jwksUrl);
        registry.add("stageminute.clerk.authorized-parties", () -> FakeClerk.AUTHORIZED_PARTY);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FreshSchema {
        /** Tests move this clock; it replaces the system clock in the application context. */
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-10-19T10:00:00Z"));
        }

        @Bean
        FlywayMigrationStrategy wipeThenMigrate() {
            return flyway -> {
                flyway.clean();
                flyway.migrate();
            };
        }
    }
}
