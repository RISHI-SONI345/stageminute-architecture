package app.stageminute.practice.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.practice.QuotaState;
import app.stageminute.practice.Reservation.Source;
import org.junit.jupiter.api.Test;

/** Today's cap and what pays for each session (03 F-049, F-052; 06 section 1). Plain Java, no database. */
class AllowanceRuleTest {

    private static final int FREE_PER_DAY = 1;
    private static final int PRO_PER_DAY = 15;

    private final AllowanceRule rule = new AllowanceRule(FREE_PER_DAY, PRO_PER_DAY);

    @Test
    void aFreePersonHasOneSessionADay() {
        assertThat(rule.cap(Plan.FREE, 0)).isEqualTo(1);
    }

    @Test
    void eachAdCreditAddsOneSession() {
        assertThat(rule.cap(Plan.FREE, 2)).isEqualTo(3);
    }

    @Test
    void aNegativeCreditCountNeverTakesASessionAway() {
        assertThat(rule.cap(Plan.FREE, -1)).isEqualTo(1);
    }

    @Test
    void proHasTheFairUseCapAndAdCreditsAddNothing() {
        assertThat(rule.cap(Plan.PRO, 0)).isEqualTo(15);
        assertThat(rule.cap(Plan.PRO, 2)).isEqualTo(15);
    }

    @Test
    void theFreeSessionIsPaidByTheFreeAllowanceAndLaterOnesByAdCredits() {
        assertThat(rule.sourceOf(Plan.FREE, 1)).isEqualTo(Source.FREE);
        assertThat(rule.sourceOf(Plan.FREE, 2)).isEqualTo(Source.REWARDED);
        assertThat(rule.sourceOf(Plan.FREE, 3)).isEqualTo(Source.REWARDED);
    }

    @Test
    void everyProSessionIsPaidByPro() {
        assertThat(rule.sourceOf(Plan.PRO, 1)).isEqualTo(Source.PRO);
        assertThat(rule.sourceOf(Plan.PRO, 15)).isEqualTo(Source.PRO);
    }

    @Test
    void aFreePersonOverTheCapIsQuotaZeroAndProIsProCap() {
        assertThat(rule.refusal(Plan.FREE)).isEqualTo(QuotaState.QUOTA_ZERO);
        assertThat(rule.refusal(Plan.PRO)).isEqualTo(QuotaState.PRO_CAP);
    }
}
