package app.stageminute.me;

import app.stageminute.account.AccountCalendar;
import app.stageminute.account.ConsentService;
import app.stageminute.account.ConsentState;
import app.stageminute.account.Deletion;
import app.stageminute.account.Profile;
import app.stageminute.account.ProfileChange;
import app.stageminute.account.ProfileService;
import app.stageminute.platform.CurrentUser;
import app.stageminute.platform.FlagKey;
import app.stageminute.platform.Flags;
import app.stageminute.platform.UserId;
import app.stageminute.practice.Practice;
import app.stageminute.practice.Quota;
import app.stageminute.reports.CarriedFocusService;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.format.DateTimeFormatter;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET}, {@code PATCH} and {@code DELETE /v1/me} (06-API section 3, 05 F-082). This module only assembles: each block comes
 * from the module that owns it, and it holds no rule of its own (AD-42). {@code PATCH} sits here, and not in
 * {@code account}, because it answers with the new snapshot.
 */
@Tag(name = "me")
@RestController
@RequestMapping(path = "/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
class MeController {

    /** What the app may change. Only these fields are read; anything else in the body is ignored. */
    record PatchMeRequest(
            @Nullable @Schema(allowableValues = {"starter", "elementary", "intermediate", "upper_intermediate"})
            String level,

            @Nullable @Schema(allowableValues = {"prep", "star"})
            String style,

            @Nullable
            @Schema(
                    description =
                            "A suggestion: IANA zone name, applied at most once a week and then ignored without an error") // copy-ok: API description for developers
            String timezone,

            @Nullable String name,

            @Nullable PatchReminders reminders) {}

    /** The reminder settings the app may change; every field is optional and only the ones sent change. */
    record PatchReminders(
            @Nullable
            @Schema(
                    nullable = true,
                    description = "Local wall-clock HH:mm, 24-hour",
                    pattern = "^([01]\\d|2[0-3]):[0-5]\\d$")
            String time,

            @Nullable @Schema(nullable = true) @JsonProperty("daily_on")
            Boolean dailyOn,

            @Nullable @Schema(nullable = true) @JsonProperty("streak_risk_on")
            Boolean streakRiskOn,

            @Nullable @Schema(nullable = true) @JsonProperty("trial_reminder_on")
            Boolean trialReminderOn) {}

    private static final DateTimeFormatter REMINDER_TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final CurrentUser currentUser;
    private final ProfileService profiles;
    private final ConsentService consents;
    private final AccountCalendar calendar;
    private final Practice practice;
    private final Flags flags;
    private final CarriedFocusService carriedFocus;
    private final Deletion deletion;

    MeController(
            CurrentUser currentUser,
            ProfileService profiles,
            ConsentService consents,
            AccountCalendar calendar,
            Practice practice,
            Flags flags,
            CarriedFocusService carriedFocus,
            Deletion deletion) {
        this.currentUser = currentUser;
        this.profiles = profiles;
        this.consents = consents;
        this.calendar = calendar;
        this.practice = practice;
        this.flags = flags;
        this.carriedFocus = carriedFocus;
        this.deletion = deletion;
    }

    @Operation(operationId = "getMe", summary = "The snapshot every screen starts from")
    @GetMapping
    MeResponse get() {
        return snapshot(currentUser.require());
    }

    @Operation(operationId = "patchMe", summary = "Save level, style, time zone or name; answers with the new snapshot")
    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    MeResponse patch(@RequestBody PatchMeRequest request) {
        UserId user = currentUser.require();
        PatchReminders reminders = request.reminders();
        profiles.patch(
                user,
                new ProfileChange(
                        request.level(),
                        request.style(),
                        request.timezone(),
                        request.name(),
                        reminders == null
                                ? null
                                : new ProfileChange.Reminders(
                                        reminders.time(),
                                        reminders.dailyOn(),
                                        reminders.streakRiskOn(),
                                        reminders.trialReminderOn())));
        return snapshot(user);
    }

    @Operation(
            operationId = "deleteMe",
            summary =
                    "Delete the account and everything of the person; online only. A repeat answers 401 account_deleted, which counts as done (05 F-082)") // copy-ok: API description for developers
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Deleted"),
        @ApiResponse(
                responseCode = "401",
                description =
                        "account_deleted (the account is already gone; the app counts it as done) or session_expired", // copy-ok: API description for developers
                content =
                        @Content(
                                mediaType = "application/problem+json",
                                schema = @Schema(ref = "#/components/schemas/Problem")))
    })
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete() {
        deletion.delete(currentUser.require());
    }

    private MeResponse snapshot(UserId user) {
        Profile profile = profiles.get(user);
        ConsentState consent = consents.state(user);
        Quota quota = practice.quota(user);
        return new MeResponse(
                new MeResponse.User(
                        user.toString(),
                        profile.name(),
                        profile.email(),
                        profile.emailIsRelay(),
                        profile.level(),
                        profile.style(),
                        profile.timezone(),
                        profile.onboarded()),
                new MeResponse.Consent(
                        consent.ai(), consent.policyVersion(), consent.needsConsent(), consent.usageData()),
                new MeResponse.Today(calendar.today(user), calendar.resetsAt(user)),
                new MeResponse.Quota(
                        quota.state().wire(),
                        quota.sessionsLeft(),
                        quota.adRewardsToday(),
                        quota.adRewardsMax(),
                        quota.resetsAt()),
                new MeResponse.Flags(
                        flags.isOn(FlagKey.EXIT_AD_ENABLED),
                        flags.isOn(FlagKey.SOFT_SHEET_ENABLED),
                        flags.isOn(FlagKey.CARRY_FIX_ENABLED),
                        flags.isOn(FlagKey.PRIVACY_STRONG_COPY)),
                new MeResponse.Reminders(
                        profile.reminders().time() == null
                                ? null
                                : profile.reminders().time().format(REMINDER_TIME),
                        profile.reminders().dailyOn(),
                        profile.reminders().streakRiskOn(),
                        profile.reminders().trialReminderOn()),
                carriedFocus
                        .forNextTopic(user)
                        .map(next -> new MeResponse.NextFocus(next.fixCode(), next.label(), next.fromSessionId()))
                        .orElse(null));
    }
}
