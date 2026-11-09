package app.stageminute.analysis.score;

/**
 * Cut-offs of the no-score gate (03 F-037, F-036). Server settings in the end; {@link #LAUNCH_DEFAULT} holds the
 * requirement values until settings override them.
 *
 * @param minDurationMs shortest scored take (20 s)
 * @param minWords fewest words in a scored take (40)
 * @param cautionDurationMs a scored take shorter than this carries a caution (30 s)
 * @param lowAudioSharePct share of low-confidence words above which the take reads low audio (10)
 */
public record GateThresholds(int minDurationMs, int minWords, int cautionDurationMs, int lowAudioSharePct) {

    public static final GateThresholds LAUNCH_DEFAULT = new GateThresholds(20_000, 40, 30_000, 10);

    /** A bad setting must not open the gate: zero minimums would score empty takes. */
    public GateThresholds {
        if (minDurationMs <= 0 || minWords <= 0) {
            throw new IllegalArgumentException("minimum length and words must be positive");
        }
        if (cautionDurationMs < minDurationMs) {
            throw new IllegalArgumentException("the caution length cannot be shorter than the minimum length");
        }
        if (lowAudioSharePct < 0 || lowAudioSharePct > 100) {
            throw new IllegalArgumentException("lowAudioSharePct is 0..100, was " + lowAudioSharePct);
        }
    }
}
