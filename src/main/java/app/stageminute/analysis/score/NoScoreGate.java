package app.stageminute.analysis.score;

/**
 * Decides, before the AI call, whether a take gets a score (03 F-037, F-036). Pure: no clock, no user, no Spring.
 *
 * <p>Order when several rules fit (proposed in step3/CONFLICTS.md C-28): no speech, not English, short take, then
 * low audio. A take that is not scored never reaches the low-audio check.
 */
public final class NoScoreGate {

    private NoScoreGate() {}

    public static GateResult check(GateInput input, GateThresholds thresholds) {
        if (input.wordCount() == 0) {
            return new GateResult(TakeStatus.NO_SPEECH, false);
        }
        if (!input.englishHeard()) {
            return new GateResult(TakeStatus.NOT_ENGLISH, false);
        }
        if (input.durationMs() < thresholds.minDurationMs() || input.wordCount() < thresholds.minWords()) {
            return new GateResult(TakeStatus.SHORT_TAKE, false);
        }
        boolean shortCaution = input.durationMs() < thresholds.cautionDurationMs();
        TakeStatus status = isLowAudio(input, thresholds) ? TakeStatus.LOW_AUDIO : TakeStatus.OK;
        return new GateResult(status, shortCaution);
    }

    /** More than the allowed share of words heard with low confidence. Whole numbers, so exactly 10% is not low. */
    private static boolean isLowAudio(GateInput input, GateThresholds thresholds) {
        return (long) input.lowConfidenceWords() * 100 > (long) thresholds.lowAudioSharePct() * input.wordCount();
    }
}
