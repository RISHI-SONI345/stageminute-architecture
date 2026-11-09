package app.stageminute.account;

import app.stageminute.platform.UserId;
import java.time.LocalTime;
import org.jspecify.annotations.Nullable;

/**
 * What the app may know about the person (06-API {@code GET /me}, {@code user} block). {@code onboarded} is
 * false until the person has chosen level and style; a returning person already has both, so the server
 * values win over whatever the phone holds (F-009). {@code reminders} are the time and switches of 02b F-061, kept here
 * so a reinstall brings them back (EC-099); the schedule itself lives on the phone.
 */
public record Profile(
        UserId id,
        @Nullable String name,
        @Nullable String email,
        boolean emailIsRelay,
        String level,
        String style,
        String timezone,
        boolean onboarded,
        Reminders reminders) {

    /**
     * The reminder time (local wall-clock, {@code null} until the person has chosen one) and the three switches.
     * The trial dates are not here: they come with the entitlement.
     */
    public record Reminders(@Nullable LocalTime time, boolean dailyOn, boolean streakRiskOn, boolean trialReminderOn) {}
}
