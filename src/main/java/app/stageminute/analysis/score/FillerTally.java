package app.stageminute.analysis.score;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Turns filler candidates and the call's labels into the filler numbers of a take (03 F-031). Pure: no clock, no user,
 * no Spring, and no words. The always-counted sounds are confirmed by code, whatever the call said; an in-context word
 * counts only when the call labelled it a filler (no label means it is not counted); hedges are counted and never
 * confirmed.
 */
public final class FillerTally {

    private static final long MS_PER_MINUTE = 60_000L;

    private FillerTally() {}

    /**
     * Counts the confirmed fillers, the hedges and the clusters of a take.
     *
     * @param candidates the candidates Metrics v2 found, in time order
     * @param labels the call's label by candidate id; ids that are not candidates are ignored
     * @param durationMs length of the take
     */
    public static FillerFacts of(
            List<FillerCandidate> candidates,
            Map<Integer, FillerLabel> labels,
            int durationMs,
            MetricsSettings settings) {
        List<FillerCandidate> confirmed = new ArrayList<>();
        int hedges = 0;
        for (FillerCandidate candidate : candidates) {
            switch (candidate.kind()) {
                case ALWAYS_COUNTED -> confirmed.add(candidate);
                case HEDGE -> hedges++;
                case IN_CONTEXT -> {
                    FillerLabel label = labels.get(candidate.id());
                    if (label == FillerLabel.FILLER) {
                        confirmed.add(candidate);
                    } else if (label == FillerLabel.HEDGE) {
                        hedges++;
                    }
                }
            }
        }
        confirmed.sort(Comparator.comparingInt(FillerCandidate::startMs));
        List<Integer> starts = confirmed.stream().map(FillerCandidate::startMs).toList();
        List<List<Integer>> groups = MetricsV2.fillerClusterGroups(starts, settings);
        return new FillerFacts(
                confirmed.size(),
                hedges,
                perMinute(confirmed.size(), durationMs),
                confirmed.stream().map(FillerCandidate::id).toList(),
                starts,
                clusterIds(confirmed, starts, groups),
                groups.stream().map(group -> group.get(0)).toList());
    }

    /** Each cluster is a run of the time-ordered confirmed fillers, so its ids are found by walking the list once. */
    private static List<List<Integer>> clusterIds(
            List<FillerCandidate> confirmed, List<Integer> starts, List<List<Integer>> groups) {
        List<List<Integer>> clusters = new ArrayList<>();
        int position = 0;
        for (List<Integer> group : groups) {
            while (!starts.get(position).equals(group.get(0))) {
                position++;
            }
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < group.size(); i++) {
                ids.add(confirmed.get(position + i).id());
            }
            clusters.add(ids);
            position += group.size();
        }
        return clusters;
    }

    private static int perMinute(int count, int durationMs) {
        if (durationMs <= 0) {
            return 0;
        }
        return (int) ((2L * count * MS_PER_MINUTE + durationMs) / (2L * durationMs));
    }
}
