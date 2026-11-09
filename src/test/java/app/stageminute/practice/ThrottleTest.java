package app.stageminute.practice;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.account.AccountService;
import app.stageminute.account.NewAccountContext;
import app.stageminute.account.ProfileChange;
import app.stageminute.account.ProfileService;
import app.stageminute.platform.SpendDecision;
import app.stageminute.platform.SpendGate.Tier;
import app.stageminute.platform.UserId;
import app.stageminute.practice.internal.FakePlans;
import app.stageminute.practice.internal.Plan;
import app.stageminute.practice.internal.PlanPortFake;
import app.stageminute.practice.internal.SpendGateFake;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Where the AI-cost throttle bites (03 F-087, D-58; #19.2): {@code practice} asks the gate BEFORE it reserves a session.
 * A paused free person gets the {@code throttled} word and nothing is reserved; a Pro person at the lowered ceiling gets
 * the Pro-cap word; {@code GET /me} says the same. A session given back because the throttle stopped the analysis asks
 * for the rewarded credit back (a seam, no-op until #24).
 */
@Import(FakePlans.class)
class ThrottleTest extends DatabaseTest {

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
    SpendGateFake gate;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void startFromAnAllowingGate() {
        gate.reset();
        plans.creditBacks().clear();
        setClock("2026-10-28T10:00:00Z");
    }

    private UserId newUser(String zone) {
        UserId user = accounts.resolve(
                "user_" + UUID.randomUUID().toString().replace("-", ""),
                new NewAccountContext("device-" + UUID.randomUUID(), "android", "10.9.2.1"));
        profiles.patch(user, new ProfileChange(null, null, zone, null));
        return user;
    }

    private void setClock(String instant) {
        clock.advance(Duration.between(clock.instant(), Instant.parse(instant)));
    }

    private Reservation reserve(UserId user) {
        return practice.reserve(user, "t_" + TAKES.incrementAndGet() + "_" + UUID.randomUUID(), TOPIC, null);
    }

    private int rows(String table, UserId user) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE user_id = :u")
                .param("u", user.value())
                .query(Integer.class)
                .single();
    }

    // ---- the pause for free people -------------------------------------------------------------------------

    @Test
    void aPausedFreePersonGetsThrottledUntilTheNextUtcMidnightAndNothingIsReserved() {
        UserId user = newUser("UTC");
        gate.setFree(SpendDecision.FREE_PAUSED);

        Reservation reservation = reserve(user);

        assertThat(reservation.state()).isEqualTo(QuotaState.THROTTLED);
        assertThat(reservation.sessionId()).isNull();
        assertThat(reservation.resetsAt()).isEqualTo(Instant.parse("2026-10-29T00:00:00Z"));
        assertThat(rows("daily_counters", user)).isZero();
        assertThat(rows("quota_events", user)).isZero();
        assertThat(rows("practice_sessions", user)).isZero();
    }

    @Test
    void thePauseEndsAtUtcMidnightWhateverZoneThePersonLivesIn() {
        UserId tokyo = newUser("Asia/Tokyo"); // already 19:00 on the 28th there; UTC midnight is 09:00 on the 29th
        gate.setFree(SpendDecision.FREE_PAUSED);

        assertThat(reserve(tokyo).resetsAt()).isEqualTo(Instant.parse("2026-10-29T00:00:00Z"));
    }

    @Test
    void anAdCreditDoesNotBypassThePauseAndAsksForTheCreditBack() {
        UserId user = newUser("UTC");
        plans.setAdCredits(user, 1);
        gate.setFree(SpendDecision.FREE_PAUSED);

        assertThat(reserve(user).state()).isEqualTo(QuotaState.THROTTLED);
        assertThat(plans.creditBacks()).containsExactly(user);
    }

    @Test
    void theGateIsAskedWithThePersonsPlanAndOnlyTheFreePauseStopsAFreePerson() {
        UserId free = newUser("UTC");
        UserId pro = newUser("UTC");
        plans.setPlan(pro, Plan.PRO);
        gate.setFree(SpendDecision.FREE_PAUSED); // the Pro answer is still ALLOW

        assertThat(reserve(pro).state()).isEqualTo(QuotaState.OK);
        assertThat(reserve(free).state()).isEqualTo(QuotaState.THROTTLED);
        assertThat(gate.asked()).containsExactly(Tier.PRO, Tier.FREE);
    }

    @Test
    void aTakeThatAlreadyHasItsSessionKeepsItWhileTheGateIsPaused() {
        UserId user = newUser("UTC");
        Reservation first = practice.reserve(user, "t_same_take", TOPIC, null);
        gate.setFree(SpendDecision.FREE_PAUSED);

        Reservation again = practice.reserve(user, "t_same_take", TOPIC, null);

        assertThat(again.state()).isEqualTo(QuotaState.OK);
        assertThat(again.sessionId()).isEqualTo(first.sessionId());
        assertThat(again.repeated()).isTrue();
    }

    @Test
    void whenThePauseLiftsTheSameFreePersonCanReserve() {
        UserId user = newUser("UTC");
        gate.setFree(SpendDecision.FREE_PAUSED);
        assertThat(reserve(user).state()).isEqualTo(QuotaState.THROTTLED);

        gate.reset();
        plans.creditBacks().clear();

        assertThat(reserve(user).state()).isEqualTo(QuotaState.OK);
    }

    // ---- the lowered ceiling for Pro --------------------------------------------------------------------------

    @Test
    void aProPersonAtTheLoweredCeilingGetsTheProCapWord() {
        UserId user = newUser("UTC");
        plans.setPlan(user, Plan.PRO);
        gate.setPro(SpendDecision.proReduced(3));

        for (int i = 0; i < 3; i++) {
            assertThat(reserve(user).state()).isEqualTo(QuotaState.OK);
        }
        Reservation fourth = reserve(user); // the normal Pro cap is 15, the lowered ceiling 3

        assertThat(fourth.state()).isEqualTo(QuotaState.PRO_CAP);
        assertThat(fourth.sessionId()).isNull();
        assertThat(rows("practice_sessions", user)).isEqualTo(3);
    }

    @Test
    void theResetTimeIsTheEarlierOfTheAccountDayAndTheUtcDayBecauseThePauseLiftsAtUtcMidnight() {
        UserId la = newUser("America/Los_Angeles"); // local midnight is 07:00 UTC on the 29th, after UTC midnight
        UserId kolkata = newUser("Asia/Kolkata"); // local midnight is 18:30 UTC on the 28th, before UTC midnight
        plans.setPlan(la, Plan.PRO);
        plans.setPlan(kolkata, Plan.PRO);
        gate.setPro(SpendDecision.proReduced(1));
        reserve(la);
        reserve(kolkata);

        assertThat(reserve(la).resetsAt()).isEqualTo(Instant.parse("2026-10-29T00:00:00Z"));
        assertThat(reserve(kolkata).resetsAt()).isEqualTo(Instant.parse("2026-10-28T18:30:00Z"));
    }

    // ---- GET /me says the same --------------------------------------------------------------------------------

    @Test
    void theQuotaBlockOfAPausedFreePersonIsThrottledWithNoAdFields() {
        UserId user = newUser("UTC");
        plans.setAdCredits(user, 1);
        gate.setFree(SpendDecision.FREE_PAUSED);

        Quota quota = practice.quota(user);

        assertThat(quota.state()).isEqualTo(QuotaState.THROTTLED);
        assertThat(quota.sessionsLeft()).isZero();
        assertThat(quota.adRewardsToday()).isNull(); // "Free users do not see the ad row while throttled"
        assertThat(quota.adRewardsMax()).isNull();
        assertThat(quota.resetsAt()).isEqualTo(Instant.parse("2026-10-29T00:00:00Z"));
    }

    @Test
    void theQuotaBlockOfAProPersonCountsToTheLoweredCeiling() {
        UserId user = newUser("UTC");
        plans.setPlan(user, Plan.PRO);
        gate.setPro(SpendDecision.proReduced(2));
        reserve(user);

        Quota left = practice.quota(user);
        assertThat(left.state()).isEqualTo(QuotaState.OK);
        assertThat(left.sessionsLeft()).isEqualTo(1);

        reserve(user);
        Quota capped = practice.quota(user);
        assertThat(capped.state()).isEqualTo(QuotaState.PRO_CAP);
        assertThat(capped.sessionsLeft()).isZero();
    }

    // ---- the throttle that began mid-take ---------------------------------------------------------------------

    @Test
    void aSessionGivenBackBecauseTheThrottleStoppedTheAnalysisIsReturnedAndAsksForTheCreditBack() {
        UserId user = newUser("UTC");
        Reservation reservation = reserve(user);
        assertThat(practice.quota(user).sessionsLeft()).isZero();

        boolean givenBack = practice.giveBack(user, reservation.sessionId(), Practice.Failure.THROTTLED);

        assertThat(givenBack).isTrue();
        assertThat(practice.quota(user).sessionsLeft()).isEqualTo(1);
        assertThat(plans.creditBacks()).containsExactly(user);
        assertThat(practice.giveBack(user, reservation.sessionId(), Practice.Failure.THROTTLED))
                .isFalse(); // a second call changes nothing
        assertThat(plans.creditBacks()).hasSize(1);
    }
}
