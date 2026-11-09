package app.stageminute.account;

import org.jspecify.annotations.Nullable;

/** What {@code PATCH /me} may change. A {@code null} field means "leave it as it is". */
public record ProfileChange(
        @Nullable String level,
        @Nullable String style,
        @Nullable String timezone,
        @Nullable String name,
        @Nullable Reminders reminders) {

    /**
     * A change to the reminder settings; every field is optional. {@code time} is {@code HH:mm}, 24-hour, in the
     * person's own wall-clock (the phone rebuilds its schedule in its own zone).
     */
    public record Reminders(
            @Nullable String time,
            @Nullable Boolean dailyOn,
            @Nullable Boolean streakRiskOn,
            @Nullable Boolean trialReminderOn) {}

    /** A change that leaves the reminders alone. */
    public ProfileChange(
            @Nullable String level, @Nullable String style, @Nullable String timezone, @Nullable String name) {
        this(level, style, timezone, name, null);
    }
}
