package app.stageminute.analysis.score;

import java.util.List;

/**
 * The filler numbers of a take (03 F-031 `facts.fillers {confirmed, per_min, hedges, items[], clusters[]}`). Ids and
 * counts only: no filler words are kept on the server (root CLAUDE.md, privacy rules; D-11).
 *
 * @param confirmed confirmed fillers in the take
 * @param hedges hedges: the hedge phrases plus the in-context words the call labelled a hedge (counted, never scored)
 * @param perMinute confirmed fillers a minute, rounded half up
 * @param confirmedIds candidate ids of the confirmed fillers, in time order
 * @param confirmedStartsMs when each confirmed filler starts, in the same order as {@code confirmedIds}
 * @param clusters candidate ids of each filler cluster (3 or more confirmed fillers in 10 s), in time order
 * @param clusterStartsMs when each cluster starts (its first filler), in the same order as {@code clusters}
 */
public record FillerFacts(
        int confirmed,
        int hedges,
        int perMinute,
        List<Integer> confirmedIds,
        List<Integer> confirmedStartsMs,
        List<List<Integer>> clusters,
        List<Integer> clusterStartsMs) {

    public FillerFacts {
        confirmedIds = List.copyOf(confirmedIds);
        confirmedStartsMs = List.copyOf(confirmedStartsMs);
        clusterStartsMs = List.copyOf(clusterStartsMs);
        clusters = clusters.stream().map(List::copyOf).toList();
    }

    public int clusterCount() {
        return clusters.size();
    }
}
