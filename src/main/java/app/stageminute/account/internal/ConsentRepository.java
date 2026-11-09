package app.stageminute.account.internal;

import app.stageminute.platform.UserId;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The SQL of consent: the columns on {@code users} and the append-only {@code consents_history}. */
@Repository
public class ConsentRepository {

    /** What is stored: agreed or not, to which policy version, and the Usage data switch. */
    public record Row(boolean ai, String version, boolean usageData) {}

    private final JdbcClient jdbc;

    public ConsentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Row> find(UserId id) {
        return jdbc.sql("SELECT ai_consent, consent_policy_version, usage_data_on FROM users WHERE id = :id")
                .param("id", id.value())
                .query((rs, n) -> new Row(
                        rs.getBoolean("ai_consent"),
                        rs.getString("consent_policy_version"),
                        rs.getBoolean("usage_data_on")))
                .optional();
    }

    /** False when the person no longer exists. */
    public boolean save(UserId id, boolean granted, String version, OffsetDateTime now, boolean usageData) {
        return jdbc.sql("UPDATE users SET ai_consent = :granted, consent_policy_version = :version,"
                                + " consent_at = :now, usage_data_on = :usage WHERE id = :id")
                        .param("granted", granted)
                        .param("version", version)
                        .param("now", now)
                        .param("usage", usageData)
                        .param("id", id.value())
                        .update()
                == 1;
    }

    public void addHistory(UserId id, String version, boolean granted, OffsetDateTime now) {
        jdbc.sql("INSERT INTO consents_history (user_id, policy_version, granted, at)"
                        + " VALUES (:id, :version, :granted, :now)")
                .param("id", id.value())
                .param("version", version)
                .param("granted", granted)
                .param("now", now)
                .update();
    }
}
