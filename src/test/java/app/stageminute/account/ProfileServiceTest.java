package app.stageminute.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.Reason;
import app.stageminute.platform.UserId;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** F-009 (level and style, the server value wins for a returning person), F-050 (time zone once a week). */
class ProfileServiceTest extends DatabaseTest {

    @Autowired
    AccountService accounts;

    @Autowired
    ProfileService profiles;

    @Autowired
    MutableClock clock;

    private UserId newUser() {
        return accounts.resolve(
                "user_" + UUID.randomUUID().toString().replace("-", ""),
                new NewAccountContext("device-" + UUID.randomUUID(), "android", "10.9.0.1"));
    }

    private static ProfileChange change(String level, String style, String zone, String name) {
        return new ProfileChange(level, style, zone, name);
    }

    @Test
    void aNewAccountIsNotOnboardedYet() {
        Profile profile = profiles.get(newUser());

        assertThat(profile.onboarded()).isFalse();
        assertThat(profile.level()).isEqualTo("starter");
        assertThat(profile.style()).isEqualTo("prep");
        assertThat(profile.name()).isNull();
    }

    @Test
    void choosingLevelAndStyleTogetherCompletesOnboarding() {
        UserId user = newUser();

        Profile profile = profiles.patch(user, change("intermediate", "star", null, null));

        assertThat(profile.onboarded()).isTrue();
        assertThat(profile.level()).isEqualTo("intermediate");
        assertThat(profile.style()).isEqualTo("star");
    }

    @Test
    void changingOnlyOneThingLeavesTheRestAlone() {
        UserId user = newUser();
        profiles.patch(user, change("intermediate", "star", null, null));

        Profile profile = profiles.patch(user, change("upper_intermediate", null, null, null));

        assertThat(profile.level()).isEqualTo("upper_intermediate");
        assertThat(profile.style()).isEqualTo("star");
        assertThat(profile.onboarded()).isTrue();
    }

    @Test
    void aOneSidedFirstChoiceDoesNotCompleteOnboarding() {
        UserId user = newUser();

        assertThat(profiles.patch(user, change("elementary", null, null, null)).onboarded())
                .isFalse();
    }

    @Test
    void anUnknownLevelOrStyleIsRefusedWithTheFieldName() {
        UserId user = newUser();

        assertThatThrownBy(() -> profiles.patch(user, change("expert", "star", null, null)))
                .isInstanceOfSatisfying(BusinessRefusal.class, refusal -> {
                    assertThat(refusal.reason()).isEqualTo(Reason.VALIDATION_FAILED);
                    assertThat(refusal.extra()).containsEntry("fields", List.of("level"));
                });
        assertThatThrownBy(() -> profiles.patch(user, change("starter", "jazz", "Nowhere/Land", null)))
                .isInstanceOfSatisfying(
                        BusinessRefusal.class,
                        refusal -> assertThat(refusal.extra()).containsEntry("fields", List.of("style", "timezone")));
        assertThat(profiles.get(user).level()).isEqualTo("starter"); // nothing was applied
    }

    @Test
    void theFirstTimeZoneSuggestionIsApplied() {
        UserId user = newUser();

        assertThat(profiles.patch(user, change(null, null, "Asia/Kolkata", null))
                        .timezone())
                .isEqualTo("Asia/Kolkata");
    }

    @Test
    void aSecondTimeZoneChangeWithinAWeekIsIgnoredQuietly() {
        UserId user = newUser();
        profiles.patch(user, change(null, null, "Asia/Kolkata", null));
        clock.advance(Duration.ofDays(3));

        Profile profile = profiles.patch(user, change(null, null, "Europe/Paris", null));

        assertThat(profile.timezone()).isEqualTo("Asia/Kolkata");
    }

    @Test
    void aTimeZoneChangeAfterAWeekIsApplied() {
        UserId user = newUser();
        profiles.patch(user, change(null, null, "Asia/Kolkata", null));
        clock.advance(Duration.ofDays(8));

        assertThat(profiles.patch(user, change(null, null, "Europe/Paris", null))
                        .timezone())
                .isEqualTo("Europe/Paris");
    }

    @Test
    void theSameTimeZoneAgainDoesNotUseUpTheWeek() {
        UserId user = newUser();
        profiles.patch(user, change(null, null, "Asia/Kolkata", null));
        clock.advance(Duration.ofDays(1));
        profiles.patch(user, change(null, null, "Asia/Kolkata", null));
        clock.advance(Duration.ofDays(7));

        assertThat(profiles.patch(user, change(null, null, "Europe/Paris", null))
                        .timezone())
                .isEqualTo("Europe/Paris");
    }

    @Test
    void theNameIsTrimmedAndLimited() {
        UserId user = newUser();

        assertThat(profiles.patch(user, change(null, null, null, "  Rishi  ")).name())
                .isEqualTo("Rishi");
        assertThatThrownBy(() -> profiles.patch(user, change(null, null, null, "x".repeat(81))))
                .isInstanceOf(BusinessRefusal.class);
        assertThatThrownBy(() -> profiles.patch(user, change(null, null, null, "   ")))
                .isInstanceOf(BusinessRefusal.class);
    }
}
