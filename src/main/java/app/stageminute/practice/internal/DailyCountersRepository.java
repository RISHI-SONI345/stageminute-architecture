package app.stageminute.practice.internal;

import app.stageminute.platform.UserId;
import java.time.LocalDate;
import java.util.Optional;
import java.util.OptionalInt;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The SQL of the daily counter row, one per person and account day (AD-11). A cap is never checked by
 * reading and then writing: {@link #tryUseSession} is one statement that only adds when the count is under
 * the cap, so two phones at the same moment cannot both pass. It holds no rule; the cap itself comes from
 * the caller.
 */
@Repository
public class DailyCountersRepository {

    private final JdbcClient jdbc;

    DailyCountersRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Makes the day's row if it is not there yet. Safe when two calls race for the first row of the day. */
    public void ensureRow(UserId user, LocalDate day) {
        jdbc.sql("INSERT INTO daily_counters (user_id, local_date) VALUES (:u, :d) ON CONFLICT DO NOTHING")
                .param("u", user.value())
                .param("d", day)
                .update();
    }

    /** Adds one used session when the count is below {@code cap}; empty means the cap is reached. */
    public OptionalInt tryUseSession(UserId user, LocalDate day, int cap) {
        return jdbc.sql("UPDATE daily_counters SET sessions_used = sessions_used + 1"
                        + " WHERE user_id = :u AND local_date = :d AND sessions_used < :cap"
                        + " RETURNING sessions_used")
                .param("u", user.value())
                .param("d", day)
                .param("cap", cap)
                .query(Integer.class)
                .optional()
                .map(OptionalInt::of)
                .orElseGet(OptionalInt::empty);
    }

    /** Takes one used session off the day (a release or a give-back); never goes below zero. */
    public void returnSession(UserId user, LocalDate day) {
        jdbc.sql("UPDATE daily_counters SET sessions_used = sessions_used - 1"
                        + " WHERE user_id = :u AND local_date = :d AND sessions_used > 0")
                .param("u", user.value())
                .param("d", day)
                .update();
    }

    /** Adds one used session with no cap: a result was delivered for a session already released. */
    public void addSession(UserId user, LocalDate day) {
        ensureRow(user, day);
        jdbc.sql("UPDATE daily_counters SET sessions_used = sessions_used + 1 WHERE user_id = :u AND local_date = :d")
                .param("u", user.value())
                .param("d", day)
                .update();
    }

    /** Counts one score dispute when the day has fewer than {@code cap}; false means the cap is reached. */
    public boolean tryCountDispute(UserId user, LocalDate day, int cap) {
        ensureRow(user, day);
        return jdbc.sql("UPDATE daily_counters SET disputes = disputes + 1"
                        + " WHERE user_id = :u AND local_date = :d AND disputes < :cap RETURNING disputes")
                .param("u", user.value())
                .param("d", day)
                .param("cap", cap)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /** Counts one unconfirmed give-back when the day has fewer than {@code cap}; false means the cap is reached. */
    public boolean tryUseUnconfirmedGiveBack(UserId user, LocalDate day, int cap) {
        return jdbc.sql("UPDATE daily_counters SET unconfirmed_give_backs = unconfirmed_give_backs + 1"
                                + " WHERE user_id = :u AND local_date = :d AND unconfirmed_give_backs < :cap")
                        .param("u", user.value())
                        .param("d", day)
                        .param("cap", cap)
                        .update()
                == 1;
    }

    /** The latest day this person has a counter row for, if any. */
    public Optional<LocalDate> lastDay(UserId user) {
        return jdbc.sql("SELECT local_date FROM daily_counters WHERE user_id = :u ORDER BY local_date DESC LIMIT 1")
                .param("u", user.value())
                .query(LocalDate.class)
                .optional();
    }

    public int sessionsUsed(UserId user, LocalDate day) {
        return jdbc.sql("SELECT sessions_used FROM daily_counters WHERE user_id = :u AND local_date = :d")
                .param("u", user.value())
                .param("d", day)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }
}
