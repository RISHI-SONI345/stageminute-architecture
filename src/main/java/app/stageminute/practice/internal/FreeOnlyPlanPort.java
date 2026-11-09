package app.stageminute.practice.internal;

import app.stageminute.platform.UserId;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/** Everyone is free with no ad credits. Used until the money tickets (#22, #24) bring the real answers. */
@Component
class FreeOnlyPlanPort implements PlanPort {

    @Override
    public Plan planOf(UserId user) {
        return Plan.FREE;
    }

    @Override
    public int adCreditsToday(UserId user, LocalDate day) {
        return 0;
    }
}
