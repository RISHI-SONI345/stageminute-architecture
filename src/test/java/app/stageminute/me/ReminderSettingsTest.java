package app.stageminute.me;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.support.Api;
import app.stageminute.support.Api.Reply;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.FakeClerk;
import app.stageminute.support.MutableClock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;

/**
 * The {@code reminders} block of {@code GET /v1/me} and {@code PATCH /v1/me} (02b F-061: "reminder time and switches
 * live on the server so reinstall restores them", 09 EC-099). The switches and the time only: no trial dates here
 * (the entitlement ticket owns those), and nothing the schedule needs beyond what the person chose.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReminderSettingsTest extends DatabaseTest {

    @Autowired
    Environment env;

    @Autowired
    MutableClock clock;

    private Api newPerson() {
        String clerk = "user_" + UUID.randomUUID().toString().replace("-", "");
        return new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")))
                .with("X-Device-Id", "device-" + UUID.randomUUID())
                .with("X-Platform", "android")
                .bearer(FakeClerk.token(clerk, clock.instant()).sign());
    }

    @Test
    void aNewPersonHasNoTimeYetAndTheDocumentedSwitches() {
        Reply me = newPerson().get("/v1/me");

        assertThat(me.status()).isEqualTo(200);
        assertThat(me.value("$.reminders.time")).isNull();
        assertThat(me.value("$.reminders.daily_on")).isEqualTo(false);
        assertThat(me.value("$.reminders.streak_risk_on")).isEqualTo(false);
        assertThat(me.value("$.reminders.trial_reminder_on")).isEqualTo(true);
    }

    @Test
    void patchSavesTheTimeAndTheSwitchesAndAnswersWithTheNewSnapshot() {
        Api person = newPerson();

        Reply patched = person.patch(
                "/v1/me",
                "{\"reminders\":{\"time\":\"20:00\",\"daily_on\":true,\"streak_risk_on\":true,\"trial_reminder_on\":false}}");

        assertThat(patched.status()).isEqualTo(200);
        assertThat(patched.text("$.reminders.time")).isEqualTo("20:00");
        assertThat(patched.value("$.reminders.daily_on")).isEqualTo(true);
        assertThat(patched.value("$.reminders.streak_risk_on")).isEqualTo(true);
        assertThat(patched.value("$.reminders.trial_reminder_on")).isEqualTo(false);
        Reply again =
                person.get("/v1/me"); // a reinstall: the same person signs in again and the server value comes back
        assertThat(again.text("$.reminders.time")).isEqualTo("20:00");
        assertThat(again.value("$.reminders.daily_on")).isEqualTo(true);
    }

    @Test
    void aPartialPatchChangesOnlyTheFieldsSent() {
        Api person = newPerson();
        person.patch("/v1/me", "{\"reminders\":{\"time\":\"07:30\",\"daily_on\":true,\"streak_risk_on\":true}}");

        Reply patched = person.patch("/v1/me", "{\"reminders\":{\"daily_on\":false}}");

        assertThat(patched.value("$.reminders.daily_on")).isEqualTo(false);
        assertThat(patched.text("$.reminders.time")).isEqualTo("07:30");
        assertThat(patched.value("$.reminders.streak_risk_on")).isEqualTo(true);
        assertThat(patched.value("$.reminders.trial_reminder_on")).isEqualTo(true);
    }

    @Test
    void aPatchWithoutTheRemindersBlockLeavesThemAlone() {
        Api person = newPerson();
        person.patch("/v1/me", "{\"reminders\":{\"time\":\"21:15\",\"daily_on\":true}}");

        Reply patched = person.patch("/v1/me", "{\"level\":\"intermediate\"}");

        assertThat(patched.text("$.reminders.time")).isEqualTo("21:15");
        assertThat(patched.value("$.reminders.daily_on")).isEqualTo(true);
    }

    @Test
    void sendingTheSameChangeAgainChangesNothing() {
        Api person = newPerson();
        String body = "{\"reminders\":{\"time\":\"06:05\",\"daily_on\":true}}";

        Reply first = person.patch("/v1/me", body);
        Reply second = person.patch("/v1/me", body);

        assertThat(second.status()).isEqualTo(200);
        assertThat(second.value("$.reminders")).isEqualTo(first.value("$.reminders"));
    }

    @Test
    void midnightAndTheLastMinuteAreValidTimes() {
        Api person = newPerson();

        assertThat(person.patch("/v1/me", "{\"reminders\":{\"time\":\"00:00\"}}")
                        .text("$.reminders.time"))
                .isEqualTo("00:00");
        assertThat(person.patch("/v1/me", "{\"reminders\":{\"time\":\"23:59\"}}")
                        .text("$.reminders.time"))
                .isEqualTo("23:59");
    }

    @Test
    void aTimeThatIsNotHourAndMinuteIsRefusedNamingTheFieldAndNothingIsApplied() {
        for (String bad : List.of("8 PM", "24:00", "12:60", "7:30", "20:00:00", "", "tonight")) {
            Api person = newPerson();
            Reply reply = person.patch(
                    "/v1/me",
                    "{\"level\":\"intermediate\",\"reminders\":{\"time\":\"" + bad + "\",\"daily_on\":true}}");

            assertThat(reply.status()).as(bad).isEqualTo(400);
            assertThat(reply.text("$.reason")).isEqualTo("validation_failed");
            assertThat(reply.value("$.fields")).isEqualTo(List.of("reminders.time"));
            Reply me = person.get("/v1/me");
            assertThat(me.text("$.user.level")).isEqualTo("starter"); // nothing was half-applied
            assertThat(me.value("$.reminders.daily_on")).isEqualTo(false);
            assertThat(me.value("$.reminders.time")).isNull();
        }
    }

    @Test
    void aSwitchThatIsNotTrueOrFalseIsAValidationFailure() {
        Reply reply = newPerson().patch("/v1/me", "{\"reminders\":{\"daily_on\":\"yes\"}}");

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.text("$.reason")).isEqualTo("validation_failed");
    }

    @Test
    void anotherPersonsSettingsAreNeverTouched() {
        Api rishi = newPerson();
        Api other = newPerson();
        other.patch("/v1/me", "{\"reminders\":{\"time\":\"09:00\",\"daily_on\":true,\"streak_risk_on\":true}}");

        rishi.patch("/v1/me", "{\"reminders\":{\"time\":\"22:00\",\"daily_on\":false,\"trial_reminder_on\":false}}");

        Reply untouched = other.get("/v1/me");
        assertThat(untouched.text("$.reminders.time")).isEqualTo("09:00");
        assertThat(untouched.value("$.reminders.daily_on")).isEqualTo(true);
        assertThat(untouched.value("$.reminders.streak_risk_on")).isEqualTo(true);
        assertThat(untouched.value("$.reminders.trial_reminder_on")).isEqualTo(true);
    }

    @Test
    void theBlockHoldsNoTrialDatesAndNoOtherField() {
        Reply me = newPerson().get("/v1/me");

        assertThat(me.value("$.reminders")).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> block = (java.util.Map<String, Object>) me.value("$.reminders");
        assertThat(block.keySet()).containsExactlyInAnyOrder("time", "daily_on", "streak_risk_on", "trial_reminder_on");
    }

    @Test
    void theChosenTimeIsNotWrittenToAnyLogLine(CapturedOutput output) {
        newPerson().patch("/v1/me", "{\"reminders\":{\"time\":\"13:37\",\"daily_on\":true}}");

        assertThat(output.getAll()).doesNotContain("13:37");
    }
}
