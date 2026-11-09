package app.stageminute.practice;

import static app.stageminute.support.LedgerAssertions.assertLedgerMatchesCounters;
import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.account.AccountService;
import app.stageminute.account.NewAccountContext;
import app.stageminute.account.ProfileChange;
import app.stageminute.account.ProfileService;
import app.stageminute.platform.UserId;
import app.stageminute.practice.internal.FakePlans;
import app.stageminute.practice.internal.Plan;
import app.stageminute.practice.internal.PlanPortFake;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The quota block of {@code GET /me} as {@code Practice.quota} (ticket #58, 03 F-049, F-051): the same cap,
 * the same day and the same four words as {@code reserve}, and a read that writes nothing. Ad fields exist
 * only for the Free plan (F-051: {@code pro_cap} and {@code throttled} never show an ad).
 */
@Import(FakePlans.class)
class QuotaReadTest extends DatabaseTest {

    private static final String TOPIC = "prep-ele-001";
    private static final AtomicInteger TAKES = new AtomicInteger();

    @Autowired
    Practice practice;

    @Autowired
    AccountService accounts;

    @Autowired
    ProfileService profiles;

    @Autowired
    PlanPortFake plans;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    private UserId newUser(String zone) {
        UserId user = accounts.resolve(
                "user_" + UUID.randomUUID().toString().replace("-", ""),
                new NewAccountContext("device-" + UUID.randomUUID(), "android", "10.9.2.1"));
        profiles.patch(user, new ProfileChange(null, null, zone, null));
        return user;
    }

    private UserId newUser() {
        return newUser("UTC");
    }

    private void setClock(String instant) {
        clock.advance(Duration.between(clock.instant(), Instant.parse(instant)));
    }

    private Reservation reserve(UserId user) {
        return practice.reserve(user, "t_" + TAKES.incrementAndGet() + "_" + UUID.randomUUID(), TOPIC, null);
    }

    private int counterRows(UserId user) {
        return jdbc.sql("SELECT count(*) FROM daily_counters WHERE user_id = :u")
                .param("u", user.value())
                .query(Integer.class)
                .single();
    }

    // --- Free plan ---

    @Test
    void aNewFreePersonIsOkWithOneSessionLeftAndNoAdRewardsYet() {
        setClock("2026-10-28T10:00:00Z");
        UserId user = newUser();

        Quota quota = practice.quota(user);

        assertThat(quota.state()).isEqualTo(QuotaState.OK);
        assertThat(quota.sessionsLeft()).isEqualTo(1);
        assertThat(quota.adRewardsToday()).isEqualTo(0);
        assertThat(quota.adRewardsMax()).isEqualTo(2); // the setting reward_grants_per_day
        assertThat(quota.resetsAt()).isEqualTo(Instant.parse("2026-10-29T00:00:00Z"));
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void afterTheFreeSessionIsReservedTheStateIsQuotaZeroWithZeroLeft() {
        setClock("2026-10-28T10:00:00Z");
        UserId user = newUser();
        reserve(user);

        Quota quota = practice.quota(user);

        assertThat(quota.state()).isEqualTo(QuotaState.QUOTA_ZERO);
        assertThat(quota.sessionsLeft()).isEqualTo(0);
        assertThat(quota.adRewardsToday()).isEqualTo(0);
        assertThat(quota.adRewardsMax()).isEqualTo(2);
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void anAdCreditOpensOneMoreSessionAndIsCounted() {
        setClock("2026-10-28T10:00:00Z");
        UserId user = newUser();
        reserve(user);
        plans.setAdCredits(user, 1);

        Quota quota = practice.quota(user);

        assertThat(quota.state()).isEqualTo(QuotaState.OK);
        assertThat(quota.sessionsLeft()).isEqualTo(1);
        assertThat(quota.adRewardsToday()).isEqualTo(1);
        assertThat(quota.adRewardsMax()).isEqualTo(2);
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void aReleasedSessionIsLeftAgain() {
        setClock("2026-10-28T10:00:00Z");
        UserId user = newUser();
        Reservation reservation = reserve(user);
        practice.release(user, reservation.sessionId(), Practice.ReleaseReason.CANCEL);

        Quota quota = practice.quota(user);

        assertThat(quota.state()).isEqualTo(QuotaState.OK);
        assertThat(quota.sessionsLeft()).isEqualTo(1);
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void theNextAccountDayIsOkAgain() {
        setClock("2026-10-28T10:00:00Z");
        UserId user = newUser();
        reserve(user);

        setClock("2026-10-29T00:00:01Z");
        Quota quota = practice.quota(user);

        assertThat(quota.state()).isEqualTo(QuotaState.OK);
        assertThat(quota.sessionsLeft()).isEqualTo(1);
        assertThat(quota.resetsAt()).isEqualTo(Instant.parse("2026-10-30T00:00:00Z"));
        assertLedgerMatchesCounters(jdbc, user);
    }

    // --- Pro plan: no ad fields at all ---

    @Test
    void aProPersonHasTheFairUseCapLeftAndNoAdFields() {
        setClock("2026-10-28T10:00:00Z");
        UserId user = newUser();
        plans.setPlan(user, Plan.PRO);
        reserve(user);

        Quota quota = practice.quota(user);

        assertThat(quota.state()).isEqualTo(QuotaState.OK);
        assertThat(quota.sessionsLeft()).isEqualTo(14); // the setting pro_sessions_per_day (15) minus one
        assertThat(quota.adRewardsToday()).isNull();
        assertThat(quota.adRewardsMax()).isNull();
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void aProPersonAtTheCapIsProCapWithNoAdFields() {
        setClock("2026-10-28T10:00:00Z");
        UserId user = newUser();
        plans.setPlan(user, Plan.PRO);
        for (int i = 0; i < 15; i++) {
            assertThat(reserve(user).state()).isEqualTo(QuotaState.OK);
        }

        Quota quota = practice.quota(user);

        assertThat(quota.state()).isEqualTo(QuotaState.PRO_CAP);
        assertThat(quota.sessionsLeft()).isEqualTo(0);
        assertThat(quota.adRewardsToday()).isNull();
        assertThat(quota.adRewardsMax()).isNull();
        assertThat(quota.resetsAt()).isEqualTo(Instant.parse("2026-10-29T00:00:00Z"));
        assertLedgerMatchesCounters(jdbc, user);
    }

    // --- the same day as reserve ---

    @Test
    void afterAZoneJumpWestTheQuotaDayIsTheLastUsedDayLikeReserve() {
        setClock("2026-10-26T20:00:00Z"); // the 27th in India, the 26th in UTC
        UserId user = newUser("Asia/Kolkata");
        reserve(user); // counted on the 27th
        jdbc.sql("UPDATE users SET timezone = 'UTC' WHERE id = :id")
                .param("id", user.value())
                .update();

        Quota quota = practice.quota(user);

        assertThat(quota.state()).isEqualTo(QuotaState.QUOTA_ZERO); // the 27th is used, as reserve says
        assertThat(quota.sessionsLeft()).isEqualTo(0);
        assertThat(quota.resetsAt()).isEqualTo(Instant.parse("2026-10-28T00:00:00Z")); // end of the 27th in UTC
        assertThat(practice.reserve(user, "t_after_jump", TOPIC, null).state()).isEqualTo(quota.state());
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void aZoneJumpEastUsesTheLaterDayAtOnceLikeReserve() {
        setClock("2026-10-26T20:00:00Z"); // the 26th in UTC, the 27th in India
        UserId user = newUser();
        reserve(user); // counted on the 26th
        jdbc.sql("UPDATE users SET timezone = 'Asia/Kolkata' WHERE id = :id")
                .param("id", user.value())
                .update();

        Quota quota = practice.quota(user);

        assertThat(quota.state()).isEqualTo(QuotaState.OK); // the 27th has no session yet
        assertThat(quota.sessionsLeft()).isEqualTo(1);
        assertThat(practice.reserve(user, "t_after_jump_east", TOPIC, null).state())
                .isEqualTo(QuotaState.OK);
        assertLedgerMatchesCounters(jdbc, user);
    }

    // --- a read writes nothing ---

    @Test
    void readingTheQuotaMakesNoCounterRowAndNoLedgerRow() {
        setClock("2026-10-28T10:00:00Z");
        UserId user = newUser();

        practice.quota(user);
        practice.quota(user);

        assertThat(counterRows(user)).isEqualTo(0);
        assertThat(jdbc.sql("SELECT count(*) FROM quota_events WHERE user_id = :u")
                        .param("u", user.value())
                        .query(Integer.class)
                        .single())
                .isEqualTo(0);
        assertLedgerMatchesCounters(jdbc, user);
    }
}
