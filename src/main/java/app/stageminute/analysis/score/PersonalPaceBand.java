package app.stageminute.analysis.score;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Chooses the pace band for a take from the person's past takes (03 F-027, requirements version 1.7). Pure: it gets
 * plain numbers, one per scored past take (the average pace of that take, oldest first), and never the take being
 * scored. Fewer than the minimum past takes gives the launch band.
 */
public final class PersonalPaceBand {

    private PersonalPaceBand() {}

    /**
     * The median of the latest past takes (the mean of the two middle values when the count is even, rounded half
     * up), brought inside the limits, plus or minus the half width, and cut to the limits.
     */
    public static PaceBand choose(List<Integer> pastTakeWpm, PersonalBandSettings settings) {
        for (int wpm : pastTakeWpm) {
            if (wpm < 0) {
                throw new IllegalArgumentException("a take's pace cannot be negative");
            }
        }
        if (pastTakeWpm.size() < settings.minTakes()) {
            return settings.limits();
        }
        int from = Math.max(0, pastTakeWpm.size() - settings.lastTakes());
        List<Integer> latest = new ArrayList<>(pastTakeWpm.subList(from, pastTakeWpm.size()));
        Collections.sort(latest);
        int size = latest.size();
        int median = size % 2 == 1 ? latest.get(size / 2) : (latest.get(size / 2 - 1) + latest.get(size / 2) + 1) / 2;

        PaceBand limits = settings.limits();
        int centre = Math.min(Math.max(median, limits.lowWpm()), limits.highWpm());
        return new PaceBand(
                Math.max(limits.lowWpm(), centre - settings.halfWidthWpm()),
                Math.min(limits.highWpm(), centre + settings.halfWidthWpm()));
    }
}
