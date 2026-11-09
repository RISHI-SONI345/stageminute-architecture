package app.stageminute.practice.internal;

import app.stageminute.platform.SpendDecision;
import app.stageminute.platform.SpendGate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** What the AI-cost meter will say, set by the test (#19.2). Allows everything until a test says otherwise. */
public class SpendGateFake implements SpendGate {

    private volatile SpendDecision free = SpendDecision.ALLOW;
    private volatile SpendDecision pro = SpendDecision.ALLOW;
    private final List<Tier> asked = new CopyOnWriteArrayList<>();

    public void setFree(SpendDecision decision) {
        free = decision;
    }

    public void setPro(SpendDecision decision) {
        pro = decision;
    }

    public void reset() {
        free = SpendDecision.ALLOW;
        pro = SpendDecision.ALLOW;
        asked.clear();
    }

    /** The tiers practice asked about, in order. */
    public List<Tier> asked() {
        return asked;
    }

    @Override
    public SpendDecision decide(Tier tier) {
        asked.add(tier);
        return tier == Tier.FREE ? free : pro;
    }
}
