package app.stageminute.trust.gates;

import app.stageminute.platform.SettingKey;

/**
 * The pass lines of {@code requirements/11-QUALITY-GATES-AND-TESTS.md} section 1, each as a named constant. They are
 * the document's numbers, not ours: never soften one here; a change needs the founder's yes and a row in
 * {@code 01-DECISIONS-AND-CHANGES.md} section H.
 */
public final class PassLines {

    /** Filler precision: at least 0.90 (PLAN section 8 P0). */
    public static final double FILLER_PRECISION_MIN = 0.90;

    /** Step-status agreement with annotators: at least 85 %. */
    public static final double STEP_AGREEMENT_MIN = 0.85;

    /** Run-to-run agreement of step status: at least 90 % ... */
    public static final double RUN_TO_RUN_MIN = 0.90;

    /** ... over 5 runs. */
    public static final int RUNS_PER_CLIP = 5;

    /** Grammar items raised on speech-recognition errors: at most 5 %. */
    public static final double GRAMMAR_ON_ASR_MAX = 0.05;

    /** Fairness across accent groups (pace, pause, filler): within 3 points. */
    public static final double FAIRNESS_MAX_POINTS = 3.0;

    /** Repeat-run score spread: SD of 3 points or less ... */
    public static final double SCORE_SD_MAX = 3.0;

    /** ... and the range within the 7-point noise band (read from the server setting, so the two never differ). */
    public static final int NOISE_BAND_POINTS = SettingKey.DELTA_SAME_WITHIN_POINTS.launchDefault();

    /** Latency of the labels-only call, end to end: p50 8 s or less ... */
    public static final double P50_MAX_SECONDS = 8.0;

    /** ... and p95 15 s or less. */
    public static final double P95_MAX_SECONDS = 15.0;

    /** Where every line above is written. */
    public static final String SOURCE = "11-QUALITY-GATES-AND-TESTS.md section 1";

    private PassLines() {}
}
