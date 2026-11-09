package app.stageminute.analysis.score;

import java.util.HashSet;
import java.util.List;

/**
 * How many moments the take map shows, and which kind goes first when there are more than fit (03 F-026: `moments[]`
 * max 3). The order is a reading, see step3/CONFLICTS.md C-39. {@link #LAUNCH_DEFAULT} holds the values.
 *
 * @param maxMoments most moments on the map (3)
 * @param kindOrder the order in which the kinds take turns: the best of each kind first, then the second best
 */
public record MomentSettings(int maxMoments, List<MomentKind> kindOrder) {

    public static final MomentSettings LAUNCH_DEFAULT =
            new MomentSettings(3, List.of(MomentKind.PAUSE, MomentKind.FILLER_CLUSTER, MomentKind.ENDING));

    public MomentSettings {
        if (maxMoments < 1) {
            throw new IllegalArgumentException("maxMoments is at least 1, was " + maxMoments);
        }
        if (kindOrder.isEmpty() || new HashSet<>(kindOrder).size() != kindOrder.size()) {
            throw new IllegalArgumentException("kindOrder needs at least one kind and no repeats");
        }
        kindOrder = List.copyOf(kindOrder);
    }
}
