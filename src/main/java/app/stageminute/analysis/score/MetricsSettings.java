package app.stageminute.analysis.score;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Thresholds of Metrics v2 (03 F-027: "thresholds in config"). {@link #LAUNCH_DEFAULT} holds the requirement values
 * until settings override them.
 *
 * @param pauseRecordMs a gap this long between two words is recorded as a pause (1.2 s)
 * @param pauseCountMs a recorded pause this long counts against flow (5 s, "mid-answer")
 * @param windowMs length of a pace window (10 s)
 * @param minFinalWindowMs a last window shorter than this is left out (5 s)
 * @param lowConfidenceBelow a word with confidence under this is low confidence (0.5)
 * @param runOnOverWords a sentence with more words than this is a run-on candidate (40)
 * @param habitMarkers sentence-initial words counted as habit markers (so, okay, right)
 * @param habitMarkerMinCount habit markers needed before the take shows a habit (3)
 * @param clusterWindowMs fillers closer together than this can form a cluster (10 s)
 * @param clusterMinFillers confirmed fillers in the window that make a cluster (3)
 * @param wordLists filler, hedge and function-word lists
 * @param paceFlagBand the band the 30 s pace flag is measured against (85 to 240; not the personal band)
 * @param paceFlagMs time in total outside that band that raises the flag (30 s)
 * @param maxMisheardWords most mis-heard content words listed for a take (3)
 */
public record MetricsSettings(
        int pauseRecordMs,
        int pauseCountMs,
        int windowMs,
        int minFinalWindowMs,
        double lowConfidenceBelow,
        int runOnOverWords,
        Set<String> habitMarkers,
        int habitMarkerMinCount,
        int clusterWindowMs,
        int clusterMinFillers,
        WordLists wordLists,
        PaceBand paceFlagBand,
        int paceFlagMs,
        int maxMisheardWords) {

    public static final MetricsSettings LAUNCH_DEFAULT = new MetricsSettings(
            1200,
            5000,
            10_000,
            5000,
            0.5,
            40,
            Set.of("so", "okay", "right"),
            3,
            10_000,
            3,
            WordLists.LAUNCH_DEFAULT,
            PaceBand.LAUNCH_DEFAULT,
            30_000,
            3);

    public MetricsSettings {
        if (pauseRecordMs <= 0 || pauseCountMs < pauseRecordMs) {
            throw new IllegalArgumentException("pause thresholds need 0 < record <= count");
        }
        if (windowMs <= 0 || minFinalWindowMs <= 0 || minFinalWindowMs > windowMs) {
            throw new IllegalArgumentException("window sizes need 0 < minFinal <= window");
        }
        if (!(lowConfidenceBelow > 0 && lowConfidenceBelow <= 1)) {
            throw new IllegalArgumentException("lowConfidenceBelow is above 0 and at most 1");
        }
        if (runOnOverWords <= 0 || habitMarkerMinCount <= 0 || clusterWindowMs <= 0 || clusterMinFillers <= 0) {
            throw new IllegalArgumentException("counts and windows must be positive");
        }
        if (wordLists == null || paceFlagBand == null) {
            throw new IllegalArgumentException("word lists and the pace flag band are required");
        }
        if (paceFlagMs <= 0 || maxMisheardWords <= 0) {
            throw new IllegalArgumentException("paceFlagMs and maxMisheardWords must be positive");
        }
        if (habitMarkers.isEmpty()) {
            throw new IllegalArgumentException("habitMarkers cannot be empty");
        }
        habitMarkers = habitMarkers.stream()
                .map(marker -> marker.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
