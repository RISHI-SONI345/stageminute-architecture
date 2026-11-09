package app.stageminute.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.stageminute.platform.BusinessRefusal;
import app.stageminute.platform.Reason;
import app.stageminute.platform.UserId;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.MutableClock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * F-079: consent is saved with the policy version and the time, withdrawing is recorded, and a new policy
 * version asks again. "Done when": withdrawing blocks the next analyze with {@code consent_off}.
 */
class ConsentServiceTest extends DatabaseTest {

    @Autowired
    AccountService accounts;

    @Autowired
    ConsentService consents;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Value("${stageminute.privacy.policy-version}")
    String current;

    private UserId newUser() {
        return accounts.resolve(
                "user_" + UUID.randomUUID().toString().replace("-", ""),
                new NewAccountContext("device-" + UUID.randomUUID(), "android", "10.9.1.1"));
    }

    private int history(UserId user) {
        return jdbc.sql("SELECT count(*) FROM consents_history WHERE user_id = :u")
                .param("u", user.value())
                .query(Integer.class)
                .single();
    }

    @Test
    void aNewAccountHasNotConsentedAndIsAskedForTheCurrentVersion() {
        ConsentState state = consents.state(newUser());

        assertThat(state.ai()).isFalse();
        assertThat(state.needsConsent()).isTrue();
        assertThat(state.policyVersion()).isEqualTo(current);
        assertThat(state.usageData()).isTrue(); // the Usage data switch starts on (F-079)
    }

    @Test
    void agreeingIsSavedWithTheVersionTheTimeAndAHistoryRow() {
        UserId user = newUser();

        consents.record(user, current, true, true);

        ConsentState state = consents.state(user);
        assertThat(state.ai()).isTrue();
        assertThat(state.needsConsent()).isFalse();
        assertThat(history(user)).isEqualTo(1);
        var row = jdbc.sql("SELECT consent_policy_version, consent_at FROM users WHERE id = :u")
                .param("u", user.value())
                .query()
                .singleRow();
        assertThat(row).containsEntry("consent_policy_version", current);
        assertThat(row.get("consent_at")).isNotNull();
        consents.require(user); // does not throw
    }

    @Test
    void withdrawingTurnsAnalysisOffAndNextAnalyzeIsRefusedWithConsentOff() {
        UserId user = newUser();
        consents.record(user, current, true, true);

        consents.record(user, current, false, true);

        assertThat(consents.state(user).ai()).isFalse();
        assertThat(history(user)).isEqualTo(2);
        assertThatThrownBy(() -> consents.require(user))
                .isInstanceOfSatisfying(
                        BusinessRefusal.class,
                        refusal -> assertThat(refusal.reason()).isEqualTo(Reason.CONSENT_OFF));
    }

    @Test
    void theUsageDataSwitchIsKeptApartFromAiConsent() {
        UserId user = newUser();

        consents.record(user, current, true, false);

        assertThat(consents.state(user).usageData()).isFalse();
        assertThat(consents.state(user).ai()).isTrue();
    }

    @Test
    void aVersionTheServerDoesNotKnowIsRefusedAndNothingIsSaved() {
        UserId user = newUser();

        assertThatThrownBy(() -> consents.record(user, "1999-01-01", true, true))
                .isInstanceOfSatisfying(BusinessRefusal.class, refusal -> {
                    assertThat(refusal.reason()).isEqualTo(Reason.VALIDATION_FAILED);
                    assertThat(refusal.extra().get("fields")).isEqualTo(java.util.List.of("policy_version"));
                });
        assertThat(history(user)).isZero();
        assertThat(consents.state(user).ai()).isFalse();
    }

    @Test
    void aNewPolicyVersionAsksAgainAndBlocksUntilTheyAgree() {
        UserId user = newUser();
        consents.record(user, current, true, true);
        ConsentService afterBump =
                new ConsentService(new app.stageminute.account.internal.ConsentRepository(jdbc), clock, "2099-01-01");

        assertThat(afterBump.state(user).needsConsent()).isTrue();
        assertThat(afterBump.state(user).ai()).isFalse();
        assertThatThrownBy(() -> afterBump.require(user)).isInstanceOf(BusinessRefusal.class);

        afterBump.record(user, "2099-01-01", true, true);
        assertThat(afterBump.state(user).needsConsent()).isFalse();
        afterBump.require(user);
    }
}
