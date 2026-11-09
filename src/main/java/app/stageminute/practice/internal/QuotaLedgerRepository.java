package app.stageminute.practice.internal;

import app.stageminute.platform.UserId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The SQL of the quota ledger (AD-12): rows are only ever added. Callers write the row in the same
 * transaction as the counter change, so the two cannot disagree. It holds no rule.
 */
@Repository
public class QuotaLedgerRepository {

    private final JdbcClient jdbc;

    QuotaLedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Adds one ledger row. A reserve is +1, a release or give-back is -1 and a commit is 0 (AD-107).
     *
     * @param sessionId the session the row belongs to, or null for a row that belongs to no session
     */
    public void append(
            UserId user,
            LocalDate day,
            QuotaEventKind kind,
            int delta,
            @Nullable UUID sessionId,
            @Nullable String reason,
            Instant at) {
        jdbc.sql("INSERT INTO quota_events (user_id, local_date, kind, delta, session_id, reason, at)"
                        + " VALUES (:u, :d, :kind, :delta, :session, :reason, :at)")
                .param("u", user.value())
                .param("d", day)
                .param("kind", kind.dbValue())
                .param("delta", delta)
                .param("session", sessionId)
                .param("reason", reason)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .update();
    }
}
