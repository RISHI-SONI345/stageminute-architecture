package app.stageminute.reports.internal;

import app.stageminute.platform.UserId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL of {@code next_focus}: one row per person (03 F-034). The row names the report it came from; the foreign key
 * removes it with that report (DB-2). No rule lives here.
 */
@Repository
public class NextFocusRepository {

    /**
     * The person's row, with the session of the report it came from.
     *
     * @param fixCode the fix carried
     * @param fromSessionId the session of the source report
     * @param style the style a step fix was set in, {@code prep} or {@code star}; null for a fix bound to no style
     * @param setAt when it was written
     */
    public record Row(
            String fixCode, UUID fromSessionId, @Nullable String style, Instant setAt) {}

    private final JdbcClient jdbc;

    NextFocusRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The row, locked until the end of the caller's transaction ({@code FOR UPDATE}). */
    public Optional<Row> lock(UserId user) {
        return find(user, " FOR UPDATE OF n");
    }

    public Optional<Row> find(UserId user) {
        return find(user, "");
    }

    private Optional<Row> find(UserId user, String lock) {
        return jdbc.sql("SELECT n.fix_code, r.session_id, n.style::text AS style, n.set_at FROM next_focus n"
                        + " JOIN reports r ON r.id = n.from_report_id AND r.user_id = n.user_id"
                        + " WHERE n.user_id = :u" + lock)
                .param("u", user.value())
                .query((rs, n) -> new Row(
                        rs.getString("fix_code"),
                        rs.getObject("session_id", UUID.class),
                        rs.getString("style"),
                        rs.getObject("set_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /** Writes the person's one row, replacing any older one. */
    public void put(UserId user, String fixCode, UUID fromReportId, @Nullable String style, Instant at) {
        jdbc.sql("""
                        INSERT INTO next_focus (user_id, fix_code, from_report_id, style, set_at)
                        VALUES (:u, :fix, :report, CAST(:style AS style_code), :at)
                        ON CONFLICT (user_id) DO UPDATE SET fix_code = EXCLUDED.fix_code,
                          from_report_id = EXCLUDED.from_report_id, style = EXCLUDED.style, set_at = EXCLUDED.set_at
                        """)
                .param("u", user.value())
                .param("fix", fixCode)
                .param("report", fromReportId)
                .param("style", style)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .update();
    }

    public void delete(UserId user) {
        jdbc.sql("DELETE FROM next_focus WHERE user_id = :u")
                .param("u", user.value())
                .update();
    }

    /** Removes the row only when it came from a report of this session. */
    public void deleteFromSession(UserId user, UUID sessionId) {
        jdbc.sql("DELETE FROM next_focus n USING reports r WHERE n.user_id = :u AND r.id = n.from_report_id"
                        + " AND r.user_id = n.user_id AND r.session_id = :s")
                .param("u", user.value())
                .param("s", sessionId)
                .update();
    }

    /** Removes the row when it is bound to a style other than this one (a row with no style stays). */
    public void deleteOtherStyle(UserId user, String style) {
        jdbc.sql(
                        "DELETE FROM next_focus WHERE user_id = :u AND style IS NOT NULL AND style <> CAST(:style AS style_code)")
                .param("u", user.value())
                .param("style", style)
                .update();
    }
}
