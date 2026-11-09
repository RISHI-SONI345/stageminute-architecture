package app.stageminute.platform;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.account.Deletion;
import app.stageminute.support.DatabaseTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 05 F-082: deleting an account is one {@code DELETE FROM users}, and nothing of the person stays except the
 * abuse signals (their user becomes null) and rows that never pointed at a user.
 */
class AccountDeletionCascadeTest extends DatabaseTest {

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-10-19T10:00:00Z");

    /** Tables that carry a user_id on purpose and are not removed by the cascade. */
    private static final List<String> KEEP_THEIR_ROWS = List.of("abuse_signals", "deleted_users");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Deletion deletion;

    private void sql(String statement, Object... params) {
        JdbcClient.StatementSpec spec = jdbc.sql(statement);
        for (int i = 0; i < params.length; i += 2) {
            spec = spec.param((String) params[i], params[i + 1]);
        }
        spec.update();
    }

    /** One row in every table that has a user_id, so the test fails when a new such table is not covered. */
    private void seedEverything(UUID u) {
        UUID session = UUID.randomUUID();
        UUID report = UUID.randomUUID();
        sql(
                "INSERT INTO users (id, clerk_user_id, level, style, timezone, last_sign_in_at, created_at)"
                        + " VALUES (:u, :c, 'starter', 'prep', 'UTC', :t, :t)",
                "u",
                u,
                "c",
                "user_" + u,
                "t",
                T);
        sql(
                "INSERT INTO consents_history (user_id, policy_version, granted, at) VALUES (:u, 'p1', true, :t)",
                "u",
                u,
                "t",
                T);
        sql("INSERT INTO seen_markers (user_id, marker, seen_at) VALUES (:u, 'first_ad_notice', :t)", "u", u, "t", T);
        sql(
                "INSERT INTO practice_sessions (id, user_id, take_key, topic_id, style, level, local_date, source,"
                        + " state, speaking_ms, reserved_at, committed_at)"
                        + " VALUES (:s, :u, :k, 't1', 'prep', 'starter', DATE '2026-10-19', 'free', 'committed',"
                        + " 30000, :t, :t)",
                "s",
                session,
                "u",
                u,
                "k",
                "take-" + u,
                "t",
                T);
        sql(
                "INSERT INTO quota_events (user_id, local_date, kind, delta, session_id, at)"
                        + " VALUES (:u, DATE '2026-10-19', 'reserve', -1, :s, :t)",
                "u",
                u,
                "s",
                session,
                "t",
                T);
        sql("INSERT INTO daily_counters (user_id, local_date) VALUES (:u, DATE '2026-10-19')", "u", u);
        sql(
                "INSERT INTO reports (id, user_id, session_id, attempt_no, style, status, score_total, formula_version,"
                        + " model_version, report, created_at)"
                        + " VALUES (:r, :u, :s, 1, 'prep', 'ok', 80, 'f1', 'm1', '{}'::jsonb, :t)",
                "r",
                report,
                "u",
                u,
                "s",
                session,
                "t",
                T);
        sql(
                "INSERT INTO facts_records (report_id, user_id, style, is_first_take, duration_ms, word_count,"
                        + " pauses_5s, fillers_confirmed, created_at)"
                        + " VALUES (:r, :u, 'prep', true, 30000, 60, 0, 1, :t)",
                "r",
                report,
                "u",
                u,
                "t",
                T);
        sql(
                "INSERT INTO score_disputes (id, report_id, user_id, area, purge_after, created_at)"
                        + " VALUES (:id, :r, :u, 'score', :t, :t)",
                "id",
                UUID.randomUUID(),
                "r",
                report,
                "u",
                u,
                "t",
                T);
        sql(
                "INSERT INTO next_focus (user_id, fix_code, from_report_id, style, set_at)"
                        + " VALUES (:u, 'add_closing', :r, 'prep', :t)",
                "u",
                u,
                "r",
                report,
                "t",
                T);
        sql(
                "INSERT INTO streak_freezes (user_id, local_date, reason) VALUES (:u, DATE '2026-10-18', 'outage')",
                "u",
                u);
        sql(
                "INSERT INTO failed_start_log (user_id, local_date, kind, at)"
                        + " VALUES (:u, DATE '2026-10-19', '5xx', :t)",
                "u",
                u,
                "t",
                T);
        sql(
                "INSERT INTO entitlements (user_id, tier, last_event_at, updated_at) VALUES (:u, 'free', :t, :t)",
                "u",
                u,
                "t",
                T);
        sql(
                "INSERT INTO ad_reward_grants (transaction_id, user_id, local_date, ad_unit, credited, received_at)"
                        + " VALUES (:id, :u, DATE '2026-10-19', 'unit1', true, :t)",
                "id",
                "tx-" + u,
                "u",
                u,
                "t",
                T);
        sql("INSERT INTO soft_sheet_state (user_id) VALUES (:u)", "u", u);
        sql(
                "INSERT INTO idempotency_entries (user_id, key, request_hash, status, response, created_at)"
                        + " VALUES (:u, 'k', '\\x01', 'done', '{}'::jsonb, :t)",
                "u",
                u,
                "t",
                T);
        sql(
                "INSERT INTO abuse_signals (user_id, device_hash, kind, at) VALUES (:u, '\\x02', 'test', :t)",
                "u",
                u,
                "t",
                T);
    }

    private List<String> tablesWithUserId() {
        return jdbc.sql("SELECT table_name FROM information_schema.columns WHERE table_schema = 'public'"
                        + " AND column_name = 'user_id' ORDER BY table_name")
                .query(String.class)
                .list();
    }

    /** Abuse rows with no user (left by an earlier deletion in this shared test database are counted too). */
    private int anonymousAbuseRows() {
        return jdbc.sql("SELECT count(*) FROM abuse_signals WHERE user_id IS NULL AND kind = 'test'")
                .query(Integer.class)
                .single();
    }

    private int rowsOf(String table, UUID user) {
        // table names come from information_schema in this test, never from input
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE user_id = :u")
                .param("u", user)
                .query(Integer.class)
                .single();
    }

    @Test
    void deletingTheUserRemovesEverythingOfTheirsExceptTheAbuseSignalRows() {
        UUID gone = UUID.randomUUID();
        UUID stays = UUID.randomUUID();
        seedEverything(gone);
        seedEverything(stays);
        sql(
                "INSERT INTO device_flags (device_hash, platform, window_start, updated_at)"
                        + " VALUES ('\\x03', 'ios', DATE '2026-10-19', :t)",
                "t",
                T);

        List<String> tables = tablesWithUserId();
        List<String> cascaded =
                tables.stream().filter(t -> !KEEP_THEIR_ROWS.contains(t)).toList();
        for (String table : cascaded) {
            assertThat(rowsOf(table, gone))
                    .as("test must seed a row in %s before the delete", table)
                    .isPositive();
        }

        int anonymousBefore = anonymousAbuseRows();

        sql("DELETE FROM users WHERE id = :u", "u", gone);

        for (String table : cascaded) {
            assertThat(rowsOf(table, gone)).as("rows left in %s", table).isZero();
            assertThat(rowsOf(table, stays))
                    .as("the other person's rows in %s", table)
                    .isPositive();
        }
        assertThat(rowsOf("abuse_signals", gone)).isZero();
        assertThat(anonymousAbuseRows()).isEqualTo(anonymousBefore + 1);
        assertThat(jdbc.sql("SELECT count(*) FROM device_flags WHERE device_hash = '\\x03'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1); // survives deletion on purpose (05 F-082)
    }

    @Test
    void everyTableThatMentionsAUserIsEitherCascadedOrOnTheKeepList() {
        // Fails when a new table gets a user_id without a foreign key that removes it with the account.
        List<String> notCascaded = jdbc.sql("SELECT t.table_name FROM information_schema.columns t"
                        + " WHERE t.table_schema = 'public' AND t.column_name = 'user_id' AND NOT EXISTS ("
                        + " SELECT 1 FROM pg_constraint c WHERE c.contype = 'f' AND c.confrelid = 'users'::regclass"
                        + " AND c.conrelid = (quote_ident(t.table_schema) || '.' || quote_ident(t.table_name))::regclass"
                        + " AND c.confdeltype = 'c') ORDER BY 1")
                .query(String.class)
                .list();

        assertThat(notCascaded).containsExactlyInAnyOrderElementsOf(KEEP_THEIR_ROWS);
    }

    /**
     * #18.1 (11 section 2 "Deletion"): the same proof through the real deletion, not a bare DELETE. Every table that
     * holds a user_id is empty for the person afterwards, except the two kept on purpose, and each thing that stays is
     * named here with its limit from 05 section 5.
     */
    @Test
    void deletingThroughTheServiceEmptiesEveryTableOfThePersonAndLeavesOnlyTheNamedRemains() {
        UUID gone = UUID.randomUUID();
        UUID stays = UUID.randomUUID();
        seedEverything(gone);
        seedEverything(stays);
        int anonymousBefore = anonymousAbuseRows();

        deletion.delete(new UserId(gone));

        for (String table : tablesWithUserId()) {
            if (KEEP_THEIR_ROWS.contains(table)) {
                continue;
            }
            assertThat(rowsOf(table, gone)).as("rows left in %s", table).isZero();
            assertThat(rowsOf(table, stays))
                    .as("the other person's rows in %s", table)
                    .isPositive();
        }
        // abuse_signals: the row stays with no user, 12 months (05 section 5, D-56).
        assertThat(rowsOf("abuse_signals", gone)).isZero();
        assertThat(anonymousAbuseRows()).isEqualTo(anonymousBefore + 1);
        // deleted_users: our opaque id and a keyed hash, 30 days, so a backup restore can delete them again (F-090).
        assertThat(rowsOf("deleted_users", gone)).isEqualTo(1);
        assertThat(rowsOf("deleted_users", stays)).isZero();
        // event_publication: the deletion event waits for its vendors and stays 30 days after it is done (05 section
        // 5).
        assertThat(jdbc.sql("SELECT count(*) FROM event_publication WHERE event_type LIKE '%Deletion%Requested'")
                        .query(Integer.class)
                        .single())
                .isPositive();
    }
}
