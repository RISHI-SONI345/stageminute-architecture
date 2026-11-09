package app.stageminute.account.internal;

import app.stageminute.platform.UserId;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The SQL of the profile columns of {@code users}. The rules (allowed values, once-a-week zone) are in {@code ProfileService}. */
@Repository
public class ProfileRepository {

    /** A stored profile row; {@code null} fields are values the person has not given. */
    public record Row(
            @Nullable String displayName,
            @Nullable String email,
            boolean emailIsRelay,
            String level,
            String style,
            String timezone,
            boolean onboarded,
            Reminders reminders) {}

    /** The reminder time and switches as stored (02b F-061). */
    public record Reminders(@Nullable LocalTime time, boolean dailyOn, boolean streakRiskOn, boolean trialReminderOn) {}

    /** What a change must know before it is applied; the row is locked until the transaction ends. */
    public record Current(String zone, @Nullable OffsetDateTime zoneChangedAt, boolean onboarded, String style) {}

    private final JdbcClient jdbc;

    ProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Row> find(UserId id) {
        return jdbc.sql("SELECT display_name, email, email_is_relay, level, style, timezone, onboarded_at,"
                        + " reminder_time, reminder_daily_on, streak_risk_on, trial_reminder_on"
                        + " FROM users WHERE id = :id")
                .param("id", id.value())
                .query((rs, n) -> new Row(
                        rs.getString("display_name"),
                        rs.getString("email"),
                        rs.getBoolean("email_is_relay"),
                        rs.getString("level"),
                        rs.getString("style"),
                        rs.getString("timezone"),
                        rs.getObject("onboarded_at") != null,
                        new Reminders(
                                rs.getObject("reminder_time", LocalTime.class),
                                rs.getBoolean("reminder_daily_on"),
                                rs.getBoolean("streak_risk_on"),
                                rs.getBoolean("trial_reminder_on"))))
                .optional();
    }

    /** The best streak kept for the person (03 F-053); 0 for an unknown account. */
    public int bestStreak(UserId id) {
        return jdbc.sql("SELECT best_streak FROM users WHERE id = :id")
                .param("id", id.value())
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    /** Raises the best streak to {@code days}; it is never lowered. Safe to repeat. */
    public void raiseBestStreak(UserId id, int days) {
        jdbc.sql("UPDATE users SET best_streak = :days WHERE id = :id AND best_streak < :days")
                .param("id", id.value())
                .param("days", days)
                .update();
    }

    /** {@code FOR UPDATE}: two changes at once wait for each other instead of overwriting. */
    public Optional<Current> lockCurrent(UserId id) {
        return jdbc.sql("SELECT timezone, timezone_changed_at, onboarded_at, style::text AS style"
                        + " FROM users WHERE id = :id FOR UPDATE")
                .param("id", id.value())
                .query((rs, n) -> new Current(
                        rs.getString("timezone"),
                        rs.getObject("timezone_changed_at", OffsetDateTime.class),
                        rs.getObject("onboarded_at") != null,
                        rs.getString("style")))
                .optional();
    }

    /** A {@code null} level, style, name or reminder field leaves the stored value as it is. */
    public void update(
            UserId id,
            @Nullable String level,
            @Nullable String style,
            @Nullable String name,
            String zone,
            @Nullable OffsetDateTime zoneChangedAt,
            boolean completesOnboarding,
            OffsetDateTime now,
            @Nullable LocalTime reminderTime,
            @Nullable Boolean dailyOn,
            @Nullable Boolean streakRiskOn,
            @Nullable Boolean trialReminderOn) {
        jdbc.sql(
                        "UPDATE users SET" // copy-ok: SQL, never shown to a person
                                + " level = CASE WHEN CAST(:level AS text) IS NULL THEN level ELSE CAST(:level AS level_code) END,"
                                + " style = CASE WHEN CAST(:style AS text) IS NULL THEN style ELSE CAST(:style AS style_code) END,"
                                + " display_name = COALESCE(CAST(:name AS text), display_name),"
                                + " timezone = :zone, timezone_changed_at = :zoneChangedAt,"
                                + " onboarded_at = CASE WHEN :completes THEN :now ELSE onboarded_at END,"
                                + " reminder_time = COALESCE(CAST(:reminderTime AS time), reminder_time),"
                                + " reminder_daily_on = COALESCE(CAST(:dailyOn AS boolean), reminder_daily_on),"
                                + " streak_risk_on = COALESCE(CAST(:streakRiskOn AS boolean), streak_risk_on),"
                                + " trial_reminder_on = COALESCE(CAST(:trialReminderOn AS boolean), trial_reminder_on)"
                                + " WHERE id = :id")
                .param("level", level)
                .param("style", style)
                .param("name", name)
                .param("zone", zone)
                .param("zoneChangedAt", zoneChangedAt)
                .param("completes", completesOnboarding)
                .param("now", now)
                .param("reminderTime", reminderTime)
                .param("dailyOn", dailyOn)
                .param("streakRiskOn", streakRiskOn)
                .param("trialReminderOn", trialReminderOn)
                .param("id", id.value())
                .update();
    }
}
