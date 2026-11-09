package app.stageminute.practice.internal;

import app.stageminute.platform.UserId;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stands in for the plan and the ad credits that {@code money} will give (#22, #24). Everyone is free with
 * no credits until a test says otherwise.
 */
public class PlanPortFake implements PlanPort {

    private final Map<UserId, Plan> plans = new ConcurrentHashMap<>();
    private final Map<UserId, Integer> credits = new ConcurrentHashMap<>();
    private final List<UserId> creditBacks = new CopyOnWriteArrayList<>();

    public void setPlan(UserId user, Plan plan) {
        plans.put(user, plan);
    }

    public void setAdCredits(UserId user, int count) {
        credits.put(user, count);
    }

    /** The people {@code practice} asked to get a rewarded credit back after the throttle blocked them (#19.2). */
    public List<UserId> creditBacks() {
        return creditBacks;
    }

    @Override
    public Plan planOf(UserId user) {
        return plans.getOrDefault(user, Plan.FREE);
    }

    @Override
    public int adCreditsToday(UserId user, LocalDate day) {
        return credits.getOrDefault(user, 0);
    }

    @Override
    public void creditBackAfterThrottle(UserId user, LocalDate day) {
        creditBacks.add(user);
    }
}
