package app.stageminute.practice.internal;

import app.stageminute.platform.DailyCaps;
import app.stageminute.platform.UserId;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/** {@code practice} owns {@code daily_counters}, so it answers the platform's {@link DailyCaps} port (AD-147). */
@Component
class DailyCapsAdapter implements DailyCaps {

    private final DailyCountersRepository counters;

    DailyCapsAdapter(DailyCountersRepository counters) {
        this.counters = counters;
    }

    @Override
    public boolean tryCount(UserId user, LocalDate day, Counter counter, int cap) {
        return switch (counter) {
            case DISPUTES -> counters.tryCountDispute(user, day, cap);
        };
    }
}
