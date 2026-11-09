package app.stageminute.reports.internal;

import app.stageminute.platform.UserId;
import app.stageminute.reports.FactsRecord;
import app.stageminute.reports.ReportSummary;
import app.stageminute.reports.ReportV2;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL of {@code reports} and {@code facts_records}. No rule lives here. */
@Repository
public class ReportsRepository {

    private final JdbcClient jdbc;

    ReportsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Adds the report row (the report JSON without {@code speech_data}) and its facts row. */
    public void insert(
            UserId user,
            ReportV2 report,
            String reportJson,
            FactsRecord facts,
            String topicId,
            Instant at,
            @Nullable String answeredJudgeModel,
            @Nullable String answeredSpeechModel) {
        OffsetDateTime when = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        ReportV2.Score score = report.score();
        jdbc.sql("""
                        INSERT INTO reports (id, user_id, session_id, attempt_no, style, status, score_total,
                          structure_points, language_points, delivery_points, overall_band, formula_version,
                          model_version, report, created_at, topic_id, answered_judge_model, answered_stt_model)
                        VALUES (:id, :u, :session, :attempt, CAST(:style AS style_code), :status, :total,
                          :structure, :language, :delivery, :band, :formula, :model, CAST(:report AS jsonb), :at, :topic, :judgeModel, :sttModel)
                        """)
                .param("id", report.analysisId())
                .param("u", user.value())
                .param("session", report.sessionId())
                .param("attempt", report.attempt())
                .param("style", report.style().wire())
                .param("status", report.status().wire())
                .param("total", score == null ? null : score.total())
                .param("structure", score == null ? null : score.parts().get(0).points())
                .param("language", score == null ? null : score.parts().get(1).points())
                .param("delivery", score == null ? null : score.parts().get(2).points())
                .param("band", score == null ? null : score.overallBand().wire())
                .param("formula", report.formulaVersion())
                .param("model", report.modelVersion())
                .param("report", reportJson)
                .param("topic", topicId)
                .param("at", when)
                .param("judgeModel", answeredJudgeModel)
                .param("sttModel", answeredSpeechModel)
                .update();
        jdbc.sql("""
                        INSERT INTO facts_records (report_id, user_id, style, is_first_take, duration_ms, word_count,
                          pauses_5s, longest_pause_ms, fillers_confirmed, fillers_per_min, first_word_ms,
                          step1, step2, step3, step4, relevance, clarity, conciseness, accuracy, fix_code,
                          focus_result, created_at, session_id, attempt_no, speech_ms, filler_clusters,
                          longest_sentence_words, run_on_labelled, window_wpm, pace_band_low, pace_band_high, hit_cap,
                          fix_target, fix_carried, fix_nothing_major, average_wpm, score_total, next_attempt_allowed)
                        VALUES (:report, :u, CAST(:style AS style_code), :first, :duration, :words,
                          :pauses, :longest, :fillers, :perMin, :firstWord,
                          :s1, :s2, :s3, :s4, :relevance, :clarity, :conciseness, :accuracy, :fix,
                          :focus, :at, :session, :attempt, :speech, :clusters,
                          :sentence, :runOn, :windows, :low, :high, :cap,
                          :target, :carried, :polish, :avg, :total, :next)
                        """)
                .param("report", facts.reportId())
                .param("u", user.value())
                .param("style", facts.style())
                .param("first", facts.attempt() == 1)
                .param("duration", facts.durationMs())
                .param("words", facts.wordCount())
                .param("pauses", facts.midAnswerPauses())
                .param("longest", facts.longestPauseMs())
                .param("fillers", facts.fillersConfirmed())
                .param("perMin", facts.fillersPerMin())
                .param("firstWord", facts.firstWordMs())
                .param("s1", facts.steps().get(0))
                .param("s2", facts.steps().get(1))
                .param("s3", facts.steps().get(2))
                .param("s4", facts.steps().get(3))
                .param("relevance", facts.relevance())
                .param("clarity", facts.clarity())
                .param("conciseness", facts.conciseness())
                .param("accuracy", facts.accuracy())
                .param("fix", facts.fixCode())
                .param("focus", facts.focusResult())
                .param("at", when)
                .param("session", facts.sessionId())
                .param("attempt", facts.attempt())
                .param("speech", facts.speechMs())
                .param("clusters", facts.fillerClusters())
                .param("sentence", facts.longestSentenceWords())
                .param("runOn", facts.runOnLabelled())
                .param(
                        "windows",
                        facts.windowWpm().stream().mapToInt(Integer::intValue).toArray())
                .param("low", facts.paceBandLow())
                .param("high", facts.paceBandHigh())
                .param("cap", facts.hitCap())
                .param("target", facts.fixTarget())
                .param("carried", facts.fixCarried())
                .param("polish", facts.fixNothingMajor())
                .param("avg", facts.averageWpm())
                .param("total", facts.scoreTotal())
                .param("next", facts.nextAttemptAllowed())
                .update();
    }

    /** The person's reports made at {@code since} or later, newest first, as list rows. {@code canContinue} here only
     * says the row is its session's last attempt; {@code Reports} adds the day check. */
    public List<ReportSummary> since(UserId user, Instant since) {
        return jdbc.sql("""
                        SELECT id, session_id, attempt_no, topic_id, style::text AS style, created_at,
                          (report -> 'facts' ->> 'duration_ms')::int AS duration_ms, status, score_total, dispute_state,
                          attempt_no = (SELECT max(r2.attempt_no) FROM reports r2
                                         WHERE r2.session_id = reports.session_id AND r2.user_id = reports.user_id)
                            AS last_attempt
                          FROM reports WHERE user_id = :u AND created_at >= :since
                         ORDER BY created_at DESC, id DESC
                        """)
                .param("u", user.value())
                .param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .query((rs, row) -> new ReportSummary(
                        rs.getObject("id", UUID.class),
                        rs.getObject("session_id", UUID.class),
                        rs.getInt("attempt_no"),
                        rs.getString("topic_id"),
                        rs.getString("style"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("duration_ms"),
                        rs.getString("status"),
                        rs.getInt("score_total"),
                        rs.getString("dispute_state"),
                        rs.getBoolean("last_attempt")))
                .list();
    }

    /** The stored report text of one of the person's reports made at {@code since} or later. */
    public Optional<String> reportSince(UserId user, UUID id, Instant since) {
        return jdbc.sql("SELECT report::text FROM reports WHERE id = :id AND user_id = :u AND created_at >= :since")
                .param("id", id)
                .param("u", user.value())
                .param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .query(String.class)
                .optional();
    }

    /**
     * Deletes one of the person's reports; the foreign keys remove its facts, its disputes and the {@code next_focus}
     * that points at it. Only the owner's row matches.
     *
     * @return false when there was no such report of this person
     */
    public boolean delete(UserId user, UUID id) {
        return jdbc.sql("DELETE FROM reports WHERE id = :id AND user_id = :u")
                        .param("id", id)
                        .param("u", user.value())
                        .update()
                == 1;
    }

    /** One counted first take for the patterns: its session and its four step labels. */
    public record FirstTake(UUID sessionId, List<String> steps) {}

    /** The person's latest scored first takes of a style, newest first. */
    public List<FirstTake> latestFirstTakes(UserId user, String style, int limit) {
        return jdbc.sql("""
                        SELECT session_id, step1, step2, step3, step4 FROM facts_records
                         WHERE user_id = :u AND style = CAST(:s AS style_code) AND is_first_take
                         ORDER BY created_at DESC, report_id DESC LIMIT :limit
                        """)
                .param("u", user.value())
                .param("s", style)
                .param("limit", limit)
                .query((rs, row) -> new FirstTake(
                        rs.getObject("session_id", UUID.class),
                        List.of(
                                rs.getString("step1"),
                                rs.getString("step2"),
                                rs.getString("step3"),
                                rs.getString("step4"))))
                .list();
    }

    /** When the second attempts of these sessions ended with the focus Done, oldest first. */
    public List<Instant> secondAttemptsDone(UserId user, List<UUID> sessionIds) {
        if (sessionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT created_at FROM facts_records
                         WHERE user_id = :u AND attempt_no = 2 AND focus_result = 'done'
                           AND session_id IN (:ids) ORDER BY created_at
                        """)
                .param("u", user.value())
                .param("ids", sessionIds)
                .query((rs, row) ->
                        rs.getObject("created_at", OffsetDateTime.class).toInstant())
                .list();
    }

    /** The facts of every scored attempt of a session, first attempt first. */
    public List<FactsRecord> attempts(UserId user, UUID sessionId) {
        return jdbc.sql("SELECT * FROM facts_records WHERE user_id = :u AND session_id = :s ORDER BY attempt_no")
                .param("u", user.value())
                .param("s", sessionId)
                .query((rs, row) -> facts(rs))
                .list();
    }

    /** The average pace of the person's latest scored takes, newest first. */
    public List<Integer> recentAverageWpm(UserId user, int limit) {
        return jdbc.sql("SELECT average_wpm FROM facts_records WHERE user_id = :u AND average_wpm IS NOT NULL"
                        + " ORDER BY created_at DESC LIMIT :limit")
                .param("u", user.value())
                .param("limit", limit)
                .query(Integer.class)
                .list();
    }

    private static FactsRecord facts(ResultSet rs) throws SQLException {
        return new FactsRecord(
                rs.getObject("report_id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getInt("attempt_no"),
                rs.getString("style"),
                rs.getInt("duration_ms"),
                rs.getInt("word_count"),
                rs.getInt("speech_ms"),
                rs.getInt("first_word_ms"),
                rs.getInt("pauses_5s"),
                rs.getInt("longest_pause_ms"),
                rs.getInt("fillers_confirmed"),
                rs.getInt("fillers_per_min"),
                rs.getInt("filler_clusters"),
                rs.getInt("longest_sentence_words"),
                rs.getBoolean("run_on_labelled"),
                ints(rs.getArray("window_wpm")),
                rs.getInt("pace_band_low"),
                rs.getInt("pace_band_high"),
                rs.getBoolean("hit_cap"),
                List.of(rs.getString("step1"), rs.getString("step2"), rs.getString("step3"), rs.getString("step4")),
                rs.getInt("relevance"),
                rs.getInt("clarity"),
                rs.getInt("conciseness"),
                rs.getInt("accuracy"),
                rs.getString("fix_code"),
                rs.getInt("fix_target"),
                rs.getBoolean("fix_carried"),
                rs.getBoolean("fix_nothing_major"),
                rs.getString("focus_result"),
                rs.getInt("average_wpm"),
                rs.getInt("score_total"),
                rs.getBoolean("next_attempt_allowed"));
    }

    private static List<Integer> ints(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        List<Integer> out = new ArrayList<>();
        Arrays.stream(values).forEach(value -> out.add(((Number) value).intValue()));
        return out;
    }
}
