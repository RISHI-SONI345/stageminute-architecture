package app.stageminute.reports.internal;

import app.stageminute.platform.UserId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL of {@code score_disputes} and the report's {@code dispute_state}. No rule lives here. */
@Repository
public class DisputesRepository {

    /**
     * One dispute row: codes and numbers, plus the note and the transcript only when the person sent them.
     *
     * @param area the area code
     * @param itemIndex the step position or suggestion id, or null
     * @param fixCode the fix id of a one-tap, or null
     */
    public record NewDispute(
            UUID id,
            UUID reportId,
            String area,
            @Nullable Integer itemIndex,
            @Nullable String note,
            @Nullable String transcript,
            @Nullable String fixCode,
            String formulaVersion,
            String promptVersion,
            Instant purgeAfter,
            Instant createdAt) {

        /** Never prints the note or the transcript (05 F-081). */
        @Override
        public String toString() {
            return "NewDispute[id=" + id + ", area=" + area + "]";
        }
    }

    private final JdbcClient jdbc;

    DisputesRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Adds the row. A second one-tap on the same report adds nothing (the unique index of V8).
     *
     * @return false when the row was not added because it repeats a one-tap
     */
    public boolean insert(UserId user, NewDispute dispute) {
        return jdbc.sql("""
                        INSERT INTO score_disputes (id, report_id, user_id, area, item_index, note, transcript_text,
                          fix_code, formula_version, prompt_version, purge_after, created_at)
                        VALUES (:id, :report, :u, :area, :index, :note, :transcript,
                          :fix, :formula, :prompt, :purge, :at)
                        ON CONFLICT (report_id) WHERE area = 'fix_first' DO NOTHING
                        """)
                        .param("id", dispute.id())
                        .param("report", dispute.reportId())
                        .param("u", user.value())
                        .param("area", dispute.area())
                        .param("index", dispute.itemIndex())
                        .param("note", dispute.note())
                        .param("transcript", dispute.transcript())
                        .param("fix", dispute.fixCode())
                        .param("formula", dispute.formulaVersion())
                        .param("prompt", dispute.promptVersion())
                        .param("purge", utc(dispute.purgeAfter()))
                        .param("at", utc(dispute.createdAt()))
                        .update()
                == 1;
    }

    /**
     * Marks the report "Sent for review" ({@code dispute_state = 'sent'}) and so locks its row until the end of the
     * transaction (a delete waits). Touches no other column.
     *
     * @return false when the person has no such report (deleted in between)
     */
    public boolean markSent(UserId user, UUID reportId) {
        return jdbc.sql("UPDATE reports SET dispute_state = 'sent' WHERE id = :id AND user_id = :u")
                        .param("id", reportId)
                        .param("u", user.value())
                        .update()
                == 1;
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
