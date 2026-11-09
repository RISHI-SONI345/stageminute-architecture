package app.stageminute.analysis.internal;

import app.stageminute.analysis.spend.SpendStore;
import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The SQL of {@code ai_spend_daily}: one row per UTC date (03 F-087, F-088). Adding a call is one {@code INSERT ... ON
 * CONFLICT DO UPDATE}, never read, add, write, so two servers or two threads cannot lose an addition. It holds no
 * rule; the meter decides.
 */
@Repository
class SpendRepository implements SpendStore {

    private final JdbcClient jdbc;

    SpendRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Day read(LocalDate utcDate) {
        return jdbc.sql("SELECT micros, calls FROM ai_spend_daily WHERE utc_date = :d")
                .param("d", utcDate)
                .query((rs, row) -> new Day(rs.getLong("micros"), rs.getInt("calls")))
                .optional()
                .orElse(new Day(0, 0));
    }

    @Override
    public Day add(LocalDate utcDate, long micros) {
        return jdbc.sql("INSERT INTO ai_spend_daily (utc_date, micros, calls) VALUES (:d, :m, 1)"
                        + " ON CONFLICT (utc_date) DO UPDATE"
                        + " SET micros = ai_spend_daily.micros + EXCLUDED.micros, calls = ai_spend_daily.calls + 1"
                        + " RETURNING micros, calls")
                .param("d", utcDate)
                .param("m", micros)
                .query((rs, row) -> new Day(rs.getLong("micros"), rs.getInt("calls")))
                .single();
    }
}
