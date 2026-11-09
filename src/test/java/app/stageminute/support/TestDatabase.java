package app.stageminute.support;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The Postgres that tests with the tag {@code db} talk to (AD-36).
 *
 * <p>On this PC, {@code server/.env} names a local Postgres 17 database for tests
 * ({@code STAGEMINUTE_TEST_DB_URL}, user, password; Gradle passes them on). Without those, as in GitHub
 * Actions, one Postgres 17 container is started with Testcontainers and shared by every test class.
 * Tests wipe and rebuild the schema, so never point this at a database you want to keep.
 */
public final class TestDatabase {

    private static final String URL;
    private static final String USER;
    private static final String PASSWORD;

    static {
        String localUrl = System.getenv("STAGEMINUTE_TEST_DB_URL");
        if (localUrl != null && !localUrl.isBlank()) {
            URL = localUrl.trim();
            // Tests wipe this database. Refuse anything that is not clearly a test database.
            if (!URL.matches(".*/[A-Za-z0-9_]*_test(\\?.*)?$")) {
                throw new IllegalStateException(
                        "STAGEMINUTE_TEST_DB_URL must name a database that ends in _test: tests wipe it");
            }
            USER = System.getenv("STAGEMINUTE_TEST_DB_USER");
            PASSWORD = System.getenv("STAGEMINUTE_TEST_DB_PASSWORD");
        } else {
            // Started once per test JVM; Testcontainers' reaper removes it when the JVM ends.
            PostgreSQLContainer container = new PostgreSQLContainer("postgres:17");
            container.start();
            URL = container.getJdbcUrl();
            USER = container.getUsername();
            PASSWORD = container.getPassword();
        }
    }

    private TestDatabase() {}

    public static String url() {
        return URL;
    }

    public static String user() {
        return USER;
    }

    public static String password() {
        return PASSWORD;
    }
}
