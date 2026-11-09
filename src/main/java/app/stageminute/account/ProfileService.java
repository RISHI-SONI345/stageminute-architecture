package app.stageminute.account;

import app.stageminute.account.internal.ProfileRepository;
import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.Reason;
import app.stageminute.platform.SettingKey;
import app.stageminute.platform.Settings;
import app.stageminute.platform.UserId;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and changes a person's profile: level, style, time zone, name and reminder settings (F-009, F-050, F-061). */
@Service
public class ProfileService {

    private static final Set<String> LEVELS = Set.of("starter", "elementary", "intermediate", "upper_intermediate");
    private static final Set<String> STYLES = Set.of("prep", "star");
    private static final int MAX_NAME_LENGTH = 80;
    /** 24-hour {@code HH:mm} and nothing else: "8 PM", "7:30" and "20:00:00" are refused. */
    private static final Pattern REMINDER_TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");

    /**
     * The stored zone changed. Published inside the change's transaction, so {@code progress} freezes the dates a move
     * to the east skipped and the streak does not break (03 F-050, F-053).
     *
     * @param user whose zone changed
     * @param from the zone before
     * @param to the zone now
     * @param at when
     */
    public record ZoneChanged(UserId user, String from, String to, Instant at) {}

    private final ProfileRepository profiles;
    private final Clock clock;
    private final Settings settings;
    private final ApplicationEventPublisher events;

    ProfileService(ProfileRepository profiles, Clock clock, Settings settings, ApplicationEventPublisher events) {
        this.profiles = profiles;
        this.clock = clock;
        this.settings = settings;
        this.events = events;
    }

    /** The best streak kept for the person (03 F-053). */
    public int bestStreak(UserId id) {
        return profiles.bestStreak(id);
    }

    /** Raises the best streak; it is never lowered (03 F-053). Safe to repeat. */
    public void raiseBestStreak(UserId id, int days) {
        profiles.raiseBestStreak(id, days);
    }

    public Profile get(UserId id) {
        return profiles.find(id)
                .map(r -> new Profile(
                        id,
                        r.displayName(),
                        r.email(),
                        r.emailIsRelay(),
                        r.level(),
                        r.style(),
                        r.timezone(),
                        r.onboarded(),
                        new Profile.Reminders(
                                r.reminders().time(),
                                r.reminders().dailyOn(),
                                r.reminders().streakRiskOn(),
                                r.reminders().trialReminderOn())))
                .orElseThrow(() -> new BusinessRefusal(Reason.ACCOUNT_DELETED));
    }

    /**
     * Applies the change, or refuses all of it naming the fields that were wrong (nothing is half-applied).
     * A time zone suggestion is applied at most once a week and is otherwise ignored without an error (F-050).
     * A new style publishes {@link StyleChanged} inside the same transaction (F-009: it clears a style-bound
     * carried fix).
     */
    @Transactional
    public Profile patch(UserId id, ProfileChange change) {
        List<String> invalid = new ArrayList<>();
        if (change.level() != null && !LEVELS.contains(change.level())) {
            invalid.add("level");
        }
        if (change.style() != null && !STYLES.contains(change.style())) {
            invalid.add("style");
        }
        if (change.timezone() != null && !isZone(change.timezone())) {
            invalid.add("timezone");
        }
        String name = change.name() == null ? null : change.name().trim();
        if (name != null && (name.isEmpty() || name.length() > MAX_NAME_LENGTH)) {
            invalid.add("name");
        }
        ProfileChange.Reminders reminders = change.reminders();
        if (reminders != null
                && reminders.time() != null
                && !REMINDER_TIME.matcher(reminders.time()).matches()) {
            invalid.add("reminders.time");
        }
        if (!invalid.isEmpty()) {
            throw new BusinessRefusal(Reason.VALIDATION_FAILED, Map.of("fields", List.copyOf(invalid)));
        }

        ProfileRepository.Current current =
                profiles.lockCurrent(id).orElseThrow(() -> new BusinessRefusal(Reason.ACCOUNT_DELETED));

        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String zone = current.zone();
        OffsetDateTime zoneChangedAt = current.zoneChangedAt();
        boolean zoneChanges = false;
        if (change.timezone() != null && !change.timezone().equals(zone) && zoneMayChange(zoneChangedAt, now)) {
            zone = change.timezone();
            zoneChangedAt = now;
            zoneChanges = true;
        }
        boolean completesOnboarding = !current.onboarded() && change.level() != null && change.style() != null;

        profiles.update(
                id,
                change.level(),
                change.style(),
                name,
                zone,
                zoneChangedAt,
                completesOnboarding,
                now,
                reminders == null || reminders.time() == null ? null : LocalTime.parse(reminders.time()),
                reminders == null ? null : reminders.dailyOn(),
                reminders == null ? null : reminders.streakRiskOn(),
                reminders == null ? null : reminders.trialReminderOn());
        if (change.style() != null && !change.style().equals(current.style())) {
            events.publishEvent(new StyleChanged(id, change.style()));
        }
        if (zoneChanges) {
            events.publishEvent(new ZoneChanged(id, current.zone(), zone, now.toInstant()));
        }
        return get(id);
    }

    private boolean zoneMayChange(@Nullable OffsetDateTime changedAt, OffsetDateTime now) {
        return changedAt == null
                || !changedAt
                        .plusDays(settings.get(SettingKey.TIMEZONE_CHANGE_INTERVAL_DAYS))
                        .isAfter(now);
    }

    private static boolean isZone(String value) {
        try {
            return ZoneId.getAvailableZoneIds().contains(value) && ZoneId.of(value) != null;
        } catch (DateTimeException e) {
            return false;
        }
    }
}
