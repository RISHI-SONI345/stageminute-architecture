package app.stageminute.me;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The one snapshot every screen starts from (06-API {@code GET /me}, F-001). In ticket #8 it holds the
 * blocks whose modules exist: the person, consent, today, the quota and the flags; #16.3 adds the next focus
 * ({@code null} when there is none); #21.1 adds the reminder settings. The plan and entitlement blocks are added by
 * the tickets that build those modules (the streak and totals live in {@code GET /me/progress}, AD-155); the app
 * treats a missing block as "not known yet", never as zero.
 *
 * <p>Wire names are written out with {@code @JsonProperty}, so the generated API file and the JSON on the
 * wire cannot disagree about a name.
 */
@NullMarked
public record MeResponse(
        User user,
        Consent consent,
        Today today,
        Quota quota,
        Flags flags,
        Reminders reminders,

        @Nullable
        @Schema(
                nullable = true,
                description =
                        "The fix carried to the next topic (F-034); null when none, or with carry_fix_enabled off")
        @JsonProperty("next_focus")
        NextFocus nextFocus) {

    public record User(
            String id,
            @Nullable @Schema(nullable = true) String name,
            @Nullable @Schema(nullable = true) String email,
            @JsonProperty("email_is_relay") boolean emailIsRelay,

            @Schema(allowableValues = {"starter", "elementary", "intermediate", "upper_intermediate"})
            String level,

            @Schema(allowableValues = {"prep", "star"}) String style,

            @Schema(description = "IANA zone name, for example Asia/Kolkata")
            String timezone,

            @Schema(description = "False until level and style were chosen; a returning person is true")
            boolean onboarded) {}

    public record Consent(
            boolean ai,
            @JsonProperty("policy_version") String policyVersion,
            @JsonProperty("needs_consent") boolean needsConsent,
            @JsonProperty("usage_data") boolean usageData) {}

    public record Today(
            @JsonProperty("local_date") LocalDate localDate,
            @JsonProperty("resets_at") Instant resetsAt) {}

    /**
     * The sessions the person can still start (03 F-049, F-051), from {@code practice}. {@code state} uses the
     * same four words as {@code POST /sessions}. The ad fields are left out (not null) when an ad adds
     * nothing: for Pro, and so for {@code pro_cap}. {@code throttled} carries none either.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Quota(
            @Schema(allowableValues = {"ok", "quota_zero", "pro_cap", "throttled"})
            String state,

            @JsonProperty("sessions_left") int sessionsLeft,

            @Nullable
            @Schema(nullable = true, description = "Left out for Pro: an ad adds nothing there")
            @JsonProperty("ad_rewards_today")
            Integer adRewardsToday,

            @Nullable
            @Schema(nullable = true, description = "Left out for Pro: an ad adds nothing there")
            @JsonProperty("ad_rewards_max")
            Integer adRewardsMax,

            @Schema(description = "When the next quota day starts; the time the next free session comes back")
            @JsonProperty("resets_at")
            Instant resetsAt) {}

    /**
     * The carried focus waiting for the next topic (03 F-034), from {@code reports}. The app shows nothing while the
     * session it came from is still open.
     */
    @Schema(name = "MeNextFocus")
    public record NextFocus(
            @JsonProperty("fix_code") String fixCode,

            @Schema(description = "The Fix Library label (C-70)")
            String label,

            @JsonProperty("from_session_id") UUID fromSessionId) {}

    /**
     * The reminder time and switches (02b F-061). They live here so a reinstall brings them back (EC-099); the schedule
     * is built on the phone. No trial dates yet: the entitlement block carries them when it exists.
     */
    public record Reminders(
            @Nullable
            @Schema(
                    nullable = true,
                    pattern = "^([01]\\d|2[0-3]):[0-5]\\d$",
                    description = "Local wall-clock HH:mm (24-hour); null until the person has chosen a time")
            String time,

            @JsonProperty("daily_on") boolean dailyOn,
            @JsonProperty("streak_risk_on") boolean streakRiskOn,
            @JsonProperty("trial_reminder_on") boolean trialReminderOn) {}

    public record Flags(
            @JsonProperty("exit_ad_enabled") boolean exitAdEnabled,
            @JsonProperty("soft_sheet_enabled") boolean softSheetEnabled,
            @JsonProperty("carry_fix_enabled") boolean carryFixEnabled,
            @JsonProperty("privacy_strong_copy") boolean privacyStrongCopy) {}
}
