package app.stageminute.analysis.spend;

import java.time.LocalDate;

/**
 * Where confirmed spend is kept: one row per UTC date (table {@code ai_spend_daily}). The server keeps it in Postgres
 * ({@code analysis.internal.SpendRepository}); a test keeps it in memory. Confirmed spend is the part of the day's
 * money that must survive a restart (F-088), so the meter never decides from a copy it holds only in memory.
 */
public interface SpendStore {

    /** What the day has cost so far and how many answered vendor calls made it. */
    record Day(long micros, int calls) {}

    /** Today's row, or zeros when there is none yet. May throw when the store cannot be read. */
    Day read(LocalDate utcDate);

    /**
     * Adds one answered call to the day: one atomic statement, safe when two calls add at the same moment. Answers the
     * day as it stands after the addition, from the same statement.
     */
    Day add(LocalDate utcDate, long micros);
}
