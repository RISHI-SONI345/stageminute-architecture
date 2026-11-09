package app.stageminute.practice.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.platform.UserId;
import app.stageminute.support.DatabaseTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/** Until money exists (#22, #24) everyone is free with no ad credits; a test can switch a person to Pro. */
@Import(FakePlans.class)
class PlanPortTest extends DatabaseTest {

    @Autowired
    PlanPort plans;

    private final UserId user = new UserId(UUID.randomUUID());
    private final LocalDate today = LocalDate.parse("2026-10-28");

    @Test
    void theFreeOnlyPortSaysFreeWithNoCredits() {
        PlanPort real = new FreeOnlyPlanPort();

        assertThat(real.planOf(user)).isEqualTo(Plan.FREE);
        assertThat(real.adCreditsToday(user, today)).isZero();
    }

    @Test
    void aTestCanBindTheProFake() {
        PlanPortFake fake = (PlanPortFake) plans;
        assertThat(fake.planOf(user)).isEqualTo(Plan.FREE);

        fake.setPlan(user, Plan.PRO);
        fake.setAdCredits(user, 2);

        assertThat(plans.planOf(user)).isEqualTo(Plan.PRO);
        assertThat(plans.adCreditsToday(user, today)).isEqualTo(2);
    }
}
