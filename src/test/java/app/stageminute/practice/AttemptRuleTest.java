package app.stageminute.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 03 F-047 and D-15: 2 attempts per topic, a third only after Better or Not yet on attempt 2. The first column counts
 * scored attempts done so far (failed and no-score takes are not attempts, so the caller never counts them). Expected
 * values are read from the D-15 sentence, not from the code.
 */
class AttemptRuleTest {

    private static final AttemptSettings DEFAULT = AttemptSettings.LAUNCH_DEFAULT;

    @ParameterizedTest(name = "after attempt {0} with focus {1}: allowed={2} ({3})")
    @CsvSource({
        // attempt 1 has no focus check, and a second attempt is always offered
        "1, NONE,    true,  SECOND_ATTEMPT",
        // attempt 2: Done ends the topic ("Next topic"); Better and Not yet offer the last try
        "2, DONE,    false, FOCUS_DONE",
        "2, BETTER,  true,  LAST_TRY",
        "2, NOT_YET, true,  LAST_TRY",
        // attempt 2 with no focus result is not a case the flow produces; fail closed (no extra analysis)
        "2, NONE,    false, FOCUS_UNKNOWN",
        // attempt 3 is the last, whatever the result
        "3, DONE,    false, ATTEMPT_CAP",
        "3, BETTER,  false, ATTEMPT_CAP",
        "3, NOT_YET, false, ATTEMPT_CAP",
        // a client asking beyond the cap is refused the same way
        "4, BETTER,  false, ATTEMPT_CAP",
    })
    void nextAttemptFollowsTheD15Table(int scored, FocusOutcome outcome, boolean allowed, NextAttemptReason reason) {
        NextAttempt next = AttemptRule.next(scored, outcome, DEFAULT);

        assertThat(next.allowed()).isEqualTo(allowed);
        assertThat(next.reason()).isEqualTo(reason);
    }

    @Test
    void theCapsAreSettingsNotLiterals() {
        // with 1 base attempt and a cap of 2, the second attempt is "last try" only after Better or Not yet
        AttemptSettings tight = new AttemptSettings(1, 2);

        assertThat(AttemptRule.next(1, FocusOutcome.NONE, tight).allowed()).isFalse();
        assertThat(AttemptRule.next(1, FocusOutcome.BETTER, tight).reason()).isEqualTo(NextAttemptReason.LAST_TRY);
        assertThat(AttemptRule.next(2, FocusOutcome.BETTER, tight).reason()).isEqualTo(NextAttemptReason.ATTEMPT_CAP);
    }

    @Test
    void launchDefaultIsTwoAttemptsAndAThirdAtMost() {
        assertThat(DEFAULT.baseAttempts()).isEqualTo(2);
        assertThat(DEFAULT.maxAttempts()).isEqualTo(3);
    }

    @Test
    void settingsMustMakeSense() {
        assertThatThrownBy(() -> new AttemptSettings(0, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttemptSettings(3, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroScoredAttemptsIsNotAQuestionToAsk() {
        assertThatThrownBy(() -> AttemptRule.next(0, FocusOutcome.NONE, DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
