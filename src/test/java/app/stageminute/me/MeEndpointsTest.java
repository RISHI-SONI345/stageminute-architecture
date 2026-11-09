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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;

/** {@code GET/PATCH /v1/me} and {@code PUT /v1/me/consent} over real HTTP (06-API section 3, F-009, F-079). */
class MeEndpointsTest extends DatabaseTest {

    @Autowired
    Environment env;

    @Autowired
    MutableClock clock;

    @Value("${stageminute.privacy.policy-version}")
    String policyVersion;

    private Api newPerson() {
        String clerk = "user_" + UUID.randomUUID().toString().replace("-", "");
        return new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")))
                .with("X-Device-Id", "device-" + UUID.randomUUID())
                .with("X-Platform", "android")
                .bearer(FakeClerk.token(clerk, clock.instant()).sign());
    }

    @Test
    void aNewPersonStartsNotOnboardedAndNeedingConsent() {
        Reply me = newPerson().get("/v1/me");

        assertThat(me.status()).isEqualTo(200);
        assertThat(me.value("$.user.onboarded")).isEqualTo(false);
        assertThat(me.value("$.user.email_is_relay")).isEqualTo(false);
        assertThat(me.value("$.user.email")).isNull();
        assertThat(me.value("$.consent.ai")).isEqualTo(false);
        assertThat(me.value("$.consent.needs_consent")).isEqualTo(true);
        assertThat(me.text("$.consent.policy_version")).isEqualTo(policyVersion);
        assertThat(me.text("$.today.local_date")).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(me.text("$.today.resets_at")).endsWith("Z");
        assertThat(me.value("$.flags.privacy_strong_copy")).isEqualTo(false);
        assertThat(me.value("$.flags.exit_ad_enabled")).isEqualTo(false);
    }

    @Test
    void patchSavesTheOnboardingChoicesAndAnswersWithTheNewSnapshot() {
        Api person = newPerson();

        Reply patched = person.patch(
                "/v1/me",
                "{\"level\":\"intermediate\",\"style\":\"star\",\"timezone\":\"Asia/Kolkata\",\"name\":\"Rishi\"}");

        assertThat(patched.status()).isEqualTo(200);
        assertThat(patched.value("$.user.onboarded")).isEqualTo(true);
        assertThat(patched.text("$.user.level")).isEqualTo("intermediate");
        assertThat(patched.text("$.user.style")).isEqualTo("star");
        assertThat(patched.text("$.user.timezone")).isEqualTo("Asia/Kolkata");
        assertThat(patched.text("$.user.name")).isEqualTo("Rishi");
        Reply again = person.get("/v1/me"); // a returning person: the server values are what comes back
        assertThat(again.text("$.user.level")).isEqualTo("intermediate");
        assertThat(again.value("$.user.onboarded")).isEqualTo(true);
    }

    @Test
    void aWrongValueIsRefusedWithTheFieldNameAndChangesNothing() {
        Api person = newPerson();

        Reply bad = person.patch("/v1/me", "{\"level\":\"expert\",\"style\":\"star\"}");

        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.text("$.reason")).isEqualTo("validation_failed");
        assertThat(bad.value("$.fields")).isEqualTo(List.of("level"));
        assertThat(person.get("/v1/me").text("$.user.style")).isEqualTo("prep");
    }

    @Test
    void aBodyThatIsNotJsonIsAValidationFailure() {
        Reply bad = newPerson().patch("/v1/me", "{not json");

        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.text("$.reason")).isEqualTo("validation_failed");
        assertThat(bad.header("Content-Type")).contains("application/problem+json");
    }

    @Test
    void extraFieldsInPatchCannotTurnOnConsentOrChangeTheAccount() {
        Api person = newPerson();

        Reply reply = person.patch(
                "/v1/me",
                "{\"ai_consent\":true,\"ai\":true,\"consent\":{\"ai\":true},\"id\":\"x\",\"clerk_user_id\":\"user_other\","
                        + "\"onboarded\":true,\"email\":\"a@b.c\"}");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.value("$.consent.ai")).isEqualTo(false);
        assertThat(reply.value("$.consent.needs_consent")).isEqualTo(true);
        assertThat(reply.value("$.user.onboarded")).isEqualTo(false);
        assertThat(reply.value("$.user.email")).isNull();
        assertThat(reply.text("$.user.id")).isNotEqualTo("x");
    }

    @Test
    void agreeingToConsentIsSavedAndNoLongerAsked() {
        Api person = newPerson();

        Reply put = person.put(
                "/v1/me/consent", "{\"ai\":true,\"policy_version\":\"" + policyVersion + "\",\"usage_data\":false}");

        assertThat(put.status()).isEqualTo(204);
        Reply me = person.get("/v1/me");
        assertThat(me.value("$.consent.ai")).isEqualTo(true);
        assertThat(me.value("$.consent.needs_consent")).isEqualTo(false);
        assertThat(me.value("$.consent.usage_data")).isEqualTo(false);
    }

    @Test
    void withdrawingConsentAsksAgain() {
        Api person = newPerson();
        person.put("/v1/me/consent", "{\"ai\":true,\"policy_version\":\"" + policyVersion + "\"}");

        Reply put = person.put("/v1/me/consent", "{\"ai\":false,\"policy_version\":\"" + policyVersion + "\"}");

        assertThat(put.status()).isEqualTo(204);
        assertThat(person.get("/v1/me").value("$.consent.needs_consent")).isEqualTo(true);
    }

    @Test
    void consentWithAMissingFieldOrAnUnknownVersionIsRefused() {
        Api person = newPerson();

        Reply missing = person.put("/v1/me/consent", "{\"policy_version\":\"" + policyVersion + "\"}");
        Reply unknown = person.put("/v1/me/consent", "{\"ai\":true,\"policy_version\":\"1999-01-01\"}");

        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.value("$.fields")).isEqualTo(List.of("ai"));
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.value("$.fields")).isEqualTo(List.of("policy_version"));
        assertThat(person.get("/v1/me").value("$.consent.ai")).isEqualTo(false);
    }

    @Test
    void theseEndpointsNeedASignedInPerson() {
        Api nobody = new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")));

        assertThat(nobody.get("/v1/me").status()).isEqualTo(401);
        assertThat(nobody.patch("/v1/me", "{}").status()).isEqualTo(401);
        assertThat(nobody.put("/v1/me/consent", "{}").status()).isEqualTo(401);
    }

    @Test
    void twoPeopleNeverSeeEachOthersData() {
        Api first = newPerson();
        Api second = newPerson();
        first.patch("/v1/me", "{\"name\":\"First Person\"}");

        assertThat(second.get("/v1/me").value("$.user.name")).isNull();
        assertThat(second.get("/v1/me").text("$.user.id"))
                .isNotEqualTo(first.get("/v1/me").text("$.user.id"));
    }
}
