package app.stageminute.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.stageminute.support.DatabaseTest;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The constraints from REVIEW-2026-10-19-DATABASE.md (DB-1 to DB-5): the database itself refuses bad rows. */
class SchemaRulesTest extends DatabaseTest {

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-10-19T10:00:00Z");

    @Autowired
    JdbcClient jdbc;

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, clerk_user_id, level, style, timezone, last_sign_in_at, created_at)"
                        + " VALUES (:id, :clerk, 'starter', 'prep', 'Asia/Kolkata', :t, :t)")
                .param("id", id)
                .param("clerk", "user_" + id)
                .param("t", T)
                .update();
        return id;
    }

    private UUID session(UUID user, String state, int speakingMs) {
        UUID id = UUID.randomUUID();
        boolean committed = state.equals("committed");
        jdbc.sql("INSERT INTO practice_sessions (id, user_id, take_key, topic_id, style, level, local_date, source,"
                        + " state, speaking_ms, reserved_at, committed_at)"
                        + " VALUES (:id, :u, :k, 't1', 'prep', 'starter', DATE '2026-10-19', 'free',"
                        + " :state, :ms, :t, :c)")
                .param("id", id)
                .param("u", user)
                .param("k", "take-" + id)
                .param("state", state)
                .param("ms", speakingMs)
                .param("t", T)
                .param("c", committed ? T : null)
                .update();
        return id;
    }

    private UUID report(UUID user, UUID session, String status, Integer score) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO reports (id, user_id, session_id, attempt_no, style, status, score_total,"
                        + " formula_version, model_version, report, created_at)"
                        + " VALUES (:id, :u, :s, 1, 'prep', :status, :score, 'f1', 'm1', '{}'::jsonb, :t)")
                .param("id", id)
                .param("u", user)
                .param("s", session)
                .param("status", status)
                .param("score", score)
                .param("t", T)
                .update();
        return id;
    }

    @Test
    void deletingAReportDoesNotShrinkTheSpeakingMinutes() { // DB-1, F-055
        UUID u = user();
        UUID s = session(u, "committed", 45_000);
        UUID r = report(u, s, "ok", 80);

        jdbc.sql("DELETE FROM reports WHERE id = :r").param("r", r).update();

        Long total = jdbc.sql("SELECT sum(speaking_ms) FROM practice_sessions WHERE user_id = :u")
                .param("u", u)
                .query(Long.class)
                .single();
        assertThat(total).isEqualTo(45_000L);
    }

    @Test
    void deletingTheReportClearsTheFixCarriedFromIt() { // DB-2
        UUID u = user();
        UUID s = session(u, "committed", 1000);
        UUID r = report(u, s, "ok", 70);
        jdbc.sql("INSERT INTO next_focus (user_id, fix_code, from_report_id, style, set_at)"
                        + " VALUES (:u, 'add_closing', :r, 'prep', :t)")
                .param("u", u)
                .param("r", r)
                .param("t", T)
                .update();

        jdbc.sql("DELETE FROM reports WHERE id = :r").param("r", r).update();

        Integer left = jdbc.sql("SELECT count(*) FROM next_focus WHERE user_id = :u")
                .param("u", u)
                .query(Integer.class)
                .single();
        assertThat(left).isZero();
    }

    @Test
    void aQuotaEventMustPointAtARealSession() { // DB-3
        UUID u = user();

        assertThatThrownBy(() -> jdbc.sql("INSERT INTO quota_events (user_id, local_date, kind, delta, session_id, at)"
                                + " VALUES (:u, DATE '2026-10-19', 'reserve', -1, :s, :t)")
                        .param("u", u)
                        .param("s", UUID.randomUUID())
                        .param("t", T)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_quota_events_practice_sessions");
    }

    @Test
    void aReportCannotNameADifferentOwnerThanItsSession() { // DB-4
        UUID owner = user();
        UUID stranger = user();
        UUID s = session(owner, "committed", 1000);

        assertThatThrownBy(() -> report(stranger, s, "ok", 80))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_reports_practice_sessions");
    }

    @Test
    void factsAndDisputesCannotNameADifferentOwnerThanTheirReport() { // DB-4
        UUID owner = user();
        UUID stranger = user();
        UUID r = report(owner, session(owner, "committed", 1000), "ok", 80);

        assertThatThrownBy(() -> jdbc.sql("INSERT INTO facts_records (report_id, user_id, style, is_first_take,"
                                + " duration_ms, word_count, pauses_5s, fillers_confirmed, created_at)"
                                + " VALUES (:r, :u, 'prep', true, 1000, 10, 0, 0, :t)")
                        .param("r", r)
                        .param("u", stranger)
                        .param("t", T)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_facts_records_reports");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO score_disputes (id, report_id, user_id, area, purge_after,"
                                + " created_at) VALUES (:id, :r, :u, 'score', :t, :t)")
                        .param("id", UUID.randomUUID())
                        .param("r", r)
                        .param("u", stranger)
                        .param("t", T)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_score_disputes_reports");
    }

    @Test
    void aDisputeAreaIsFromTheClosedListAndAOneTapHoldsNoWordsAndHappensOncePerReport() { // C-67, AD-147
        UUID owner = user();
        UUID r = report(owner, session(owner, "committed", 1000), "ok", 80);

        assertThatThrownBy(() -> dispute(r, owner, "accent", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_score_disputes_area");
        assertThatThrownBy(() -> dispute(r, owner, "structure_step", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_score_disputes_index");
        assertThatThrownBy(() -> dispute(r, owner, "fix_first", "a note", null, "add_closing"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_score_disputes_one_tap_no_words");
        assertThatThrownBy(() -> dispute(r, owner, "fix_first", null, "the words", "add_closing"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_score_disputes_one_tap_no_words");
        assertThatThrownBy(() -> dispute(r, owner, "fix_first", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_score_disputes_one_tap_no_words");

        dispute(r, owner, "fix_first", null, null, "add_closing");
        assertThatThrownBy(() -> dispute(r, owner, "fix_first", null, null, "add_closing"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_score_disputes_one_tap");
        dispute(r, owner, "other", "a note", "the words", null); // a full dispute may carry both
    }

    private void dispute(UUID report, UUID user, String area, String note, String transcript, String fixCode) {
        jdbc.sql("INSERT INTO score_disputes (id, report_id, user_id, area, note, transcript_text, fix_code,"
                        + " purge_after, created_at) VALUES (:id, :r, :u, :area, :note, :tr, :fix, :t, :t)")
                .param("id", UUID.randomUUID())
                .param("r", report)
                .param("u", user)
                .param("area", area)
                .param("note", note)
                .param("tr", transcript)
                .param("fix", fixCode)
                .param("t", T)
                .update();
    }

    @Test
    void aCommittedSessionNeedsItsCommitTimeAndAReservedOneMustNotHaveIt() { // DB-5
        UUID u = user();

        assertThatThrownBy(() -> jdbc.sql("INSERT INTO practice_sessions (id, user_id, take_key, topic_id, style,"
                                + " level, local_date, source, state, reserved_at)"
                                + " VALUES (:id, :u, 'k1', 't', 'prep', 'starter', DATE '2026-10-19', 'free',"
                                + " 'committed', :t)")
                        .param("id", UUID.randomUUID())
                        .param("u", u)
                        .param("t", T)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_practice_sessions_committed");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO practice_sessions (id, user_id, take_key, topic_id, style,"
                                + " level, local_date, source, state, reserved_at, committed_at)"
                                + " VALUES (:id, :u, 'k2', 't', 'prep', 'starter', DATE '2026-10-19', 'free',"
                                + " 'reserved', :t, :t)")
                        .param("id", UUID.randomUUID())
                        .param("u", u)
                        .param("t", T)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_practice_sessions_committed");
    }

    @Test
    void anOkReportMustHaveAScoreButANoScoreReportMayNot() { // DB-5
        UUID u = user();
        UUID s = session(u, "committed", 1000);

        assertThatThrownBy(() -> report(u, s, "ok", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_reports_ok_has_score");
        UUID s2 = session(u, "committed", 1000);
        assertThat(report(u, s2, "short_take", null)).isNotNull();
    }

    @Test
    void levelAndStyleComeFromTheOneSharedList() { // DB-6
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO users (id, clerk_user_id, level, style, timezone,"
                                + " last_sign_in_at, created_at)"
                                + " VALUES (:id, :c, 'expert', 'prep', 'UTC', :t, :t)")
                        .param("id", UUID.randomUUID())
                        .param("c", "bad_" + UUID.randomUUID())
                        .param("t", T)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("level_code_values");
    }
}
