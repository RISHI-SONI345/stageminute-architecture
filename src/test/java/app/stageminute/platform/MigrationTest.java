package app.stageminute.platform;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.support.TestDatabase;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Ticket #4: V1 builds the whole schema from an empty database on Postgres 17, and the privacy rule "no audio
 * table, no transcript column" holds (05 section 5, root rule 4).
 */
@Tag("db")
class MigrationTest {

    private static JdbcClient jdbc;
    private static int migrationsApplied;

    @BeforeAll
    static void migrateAnEmptyDatabase() {
        Flyway flyway = Flyway.configure()
                .dataSource(TestDatabase.url(), TestDatabase.user(), TestDatabase.password())
                .cleanDisabled(false)
                .load();
        flyway.clean(); // empty database
        migrationsApplied = flyway.migrate().migrationsExecuted;
        DataSource dataSource =
                new DriverManagerDataSource(TestDatabase.url(), TestDatabase.user(), TestDatabase.password());
        jdbc = JdbcClient.create(dataSource);
    }

    private static List<String> tables() {
        return jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
                        + " AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'"
                        + " ORDER BY table_name")
                .query(String.class)
                .list();
    }

    @Test
    void everyMigrationRunsInOrderOnAnEmptyDatabase() {
        assertThat(migrationsApplied).isEqualTo(11);
        assertThat(jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                        .query(String.class)
                        .list())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");
    }

    @Test
    void migrationsCreateEveryTableOf03DatabaseAndModulithsEventTable() {
        assertThat(tables())
                .containsExactlyInAnyOrder(
                        "abuse_signals",
                        "admin_audit",
                        "ad_reward_grants",
                        "ai_spend_daily",
                        "app_settings",
                        "consents_history",
                        "daily_counters",
                        "deleted_users",
                        "device_flags",
                        "entitlements",
                        "event_publication",
                        "facts_records",
                        "failed_start_log",
                        "feature_flags",
                        "flag_audit",
                        "idempotency_entries",
                        "ip_new_accounts",
                        "job_locks",
                        "next_focus",
                        "outage_windows",
                        "practice_sessions",
                        "quota_events",
                        "reports",
                        "score_disputes",
                        "seen_markers",
                        "soft_sheet_state",
                        "streak_freezes",
                        "users",
                        "webhook_events");
    }

    @Test
    void everyTableHasAPrimaryKey() {
        List<String> withoutKey = jdbc.sql(
                        "SELECT t.table_name FROM information_schema.tables t WHERE t.table_schema = 'public'"
                                + " AND t.table_type = 'BASE TABLE' AND t.table_name <> 'flyway_schema_history'"
                                + " AND NOT EXISTS (SELECT 1 FROM information_schema.table_constraints c"
                                + " WHERE c.table_schema = 'public' AND c.table_name = t.table_name"
                                + " AND c.constraint_type = 'PRIMARY KEY')")
                .query(String.class)
                .list();

        assertThat(withoutKey).isEmpty();
    }

    @Test
    void everyForeignKeyIsNamedAndItsFirstColumnStartsAnIndex() {
        // review DB-8: names; design rule: Postgres does not index foreign keys, so we do
        List<String> unnamed = jdbc.sql("SELECT conname FROM pg_constraint WHERE contype = 'f'"
                        + " AND connamespace = 'public'::regnamespace AND conname NOT LIKE 'fk\\_%'")
                .query(String.class)
                .list();
        assertThat(unnamed).as("foreign keys without an fk_ name").isEmpty();

        List<String> unindexed = jdbc.sql("SELECT c.conname FROM pg_constraint c WHERE c.contype = 'f'"
                        + " AND c.connamespace = 'public'::regnamespace AND NOT EXISTS ("
                        + " SELECT 1 FROM pg_index i WHERE i.indrelid = c.conrelid"
                        + " AND i.indkey[0] = c.conkey[1])")
                .query(String.class)
                .list();
        assertThat(unindexed)
                .as("foreign keys whose first column starts no index")
                .isEmpty();
    }

    @Test
    void thereIsNoAudioTableAndNoTranscriptColumnExceptTheOptInDisputeCopy() {
        assertThat(tables()).noneMatch(t -> t.matches(".*(audio|recording|transcript|speech).*"));

        // Columns whose name suggests speech content. Only numbers (counts, durations) may carry such a name,
        // plus the one opt-in dispute transcript (05 section 5, D-18).
        List<String> speechColumns = jdbc.sql("SELECT table_name || '.' || column_name FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'"
                        + " AND column_name ~ '(audio|record|transcript|speech|utterance|word|filler|quote)'"
                        + " AND data_type NOT IN ('integer','smallint','bigint','numeric')"
                        + " ORDER BY 1")
                .query(String.class)
                .list();

        assertThat(speechColumns).containsExactly("score_disputes.transcript_text");
    }

    @Test
    void speechDataNeverHasAColumnAnywhere() {
        Integer count = jdbc.sql("SELECT count(*) FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND column_name = 'speech_data'")
                .query(Integer.class)
                .single();

        assertThat(count).isZero();
    }

    @Test
    void tablesCarryRetentionAndPrivacyComments() {
        String comment = jdbc.sql("SELECT obj_description('reports'::regclass, 'pg_class')")
                .query(String.class)
                .single();

        assertThat(comment).contains("No audio and no transcript");
    }
}
