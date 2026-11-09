package app.stageminute.trust.gates;

import app.stageminute.trust.measures.ScoreSpread;
import app.stageminute.trust.measures.WaitTimes;
import app.stageminute.trust.stats.Interval;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * Turns measured numbers into gate lines. A number that is not a number (no data) is NOT_MEASURED, never a pass. A
 * comparison is made on the exact value; only the printing is rounded, so a number that prints as the pass line but is
 * below it still fails.
 */
public final class Gates {

    private static final double PERCENT = 100.0;
    private static final double MICROS_PER_DOLLAR = 1_000_000.0;
    private static final String NO_RANGE = "no range";

    private Gates() {}

    public static Gate fillerPrecision(double precision, Interval range) {
        return atLeast(
                "filler_precision",
                "Filler precision",
                precision,
                percent(precision),
                percentRange(range),
                "at least " + wholePercent(PassLines.FILLER_PRECISION_MIN),
                PassLines.FILLER_PRECISION_MIN);
    }

    public static Gate stepAgreement(double agreement, double kappa, int cells, Interval range) {
        String measured = percent(agreement) + String.format(Locale.ROOT, " (kappa %.3f, %d cells)", kappa, cells);
        return atLeast(
                "step_agreement",
                "Step-status agreement with annotators",
                agreement,
                measured,
                percentRange(range),
                "at least " + wholePercent(PassLines.STEP_AGREEMENT_MIN),
                PassLines.STEP_AGREEMENT_MIN);
    }

    public static Gate runToRun(double agreement, double kappa, int runsPerClip, Interval range) {
        String passLine =
                "at least " + wholePercent(PassLines.RUN_TO_RUN_MIN) + " over " + PassLines.RUNS_PER_CLIP + " runs";
        if (runsPerClip < PassLines.RUNS_PER_CLIP) {
            return gate(
                    "run_to_run",
                    "Run-to-run agreement of step status",
                    "only " + runsPerClip + " runs of each clip, " + PassLines.RUNS_PER_CLIP + " needed",
                    percentRange(range),
                    passLine,
                    Status.NOT_MEASURED);
        }
        String measured = percent(agreement) + String.format(Locale.ROOT, " (kappa %.3f, %d runs)", kappa, runsPerClip);
        return atLeast(
                "run_to_run",
                "Run-to-run agreement of step status",
                agreement,
                measured,
                percentRange(range),
                passLine,
                PassLines.RUN_TO_RUN_MIN);
    }

    public static Gate grammarOnAsrErrors(double share, Interval range) {
        return atMost(
                "grammar_on_asr_errors",
                "Grammar items raised on speech-recognition errors",
                share,
                percent(share),
                percentRange(range),
                "at most " + wholePercent(PassLines.GRAMMAR_ON_ASR_MAX),
                PassLines.GRAMMAR_ON_ASR_MAX);
    }

    /** One of the three fairness gates; {@code measure} is {@code pace}, {@code pause} or {@code filler}. */
    public static Gate fairness(String measure, double spreadPoints, Interval range) {
        String rangeText = range.computable()
                ? String.format(Locale.ROOT, "%.2f to %.2f points", range.low(), range.high())
                : NO_RANGE;
        return atMost(
                "fairness_" + measure,
                "Fairness across accent groups: " + measure,
                spreadPoints,
                Double.isNaN(spreadPoints) ? "not measured" : String.format(Locale.ROOT, "%.2f points", spreadPoints),
                rangeText,
                "within " + wholeNumber(PassLines.FAIRNESS_MAX_POINTS) + " points",
                PassLines.FAIRNESS_MAX_POINTS);
    }

    public static Gate scoreSpread(ScoreSpread.Result spread) {
        String passLine = "SD of " + wholeNumber(PassLines.SCORE_SD_MAX) + " points or less; range within the "
                + PassLines.NOISE_BAND_POINTS + "-point noise band";
        if (Double.isNaN(spread.worstSd())) {
            return gate(
                    "score_spread",
                    "Repeat-run score spread",
                    "no clip has two scored runs",
                    NO_RANGE,
                    passLine,
                    Status.NOT_MEASURED);
        }
        String measured = String.format(
                Locale.ROOT,
                "worst SD %.2f, worst range %.1f points (%d clips)",
                spread.worstSd(),
                spread.worstRange(),
                spread.clips());
        boolean pass = spread.worstSd() <= PassLines.SCORE_SD_MAX && spread.worstRange() <= PassLines.NOISE_BAND_POINTS;
        return gate(
                "score_spread",
                "Repeat-run score spread",
                measured,
                NO_RANGE,
                passLine,
                pass ? Status.PASS : Status.FAIL);
    }

    /** Printed with no pass or fail: the target is set on the labelled set. */
    public static Gate excellentShare(double share, Interval range) {
        return gate(
                "excellent_share",
                "Excellent share",
                percent(share),
                percentRange(range),
                "not set (the target is set on the labelled set)",
                Double.isNaN(share) ? Status.NOT_MEASURED : Status.NOT_SET);
    }

    public static Gate latency(WaitTimes.Result wait) {
        String passLine = "p50 " + wholeNumber(PassLines.P50_MAX_SECONDS) + " s or less, p95 "
                + wholeNumber(PassLines.P95_MAX_SECONDS) + " s or less";
        if (Double.isNaN(wait.p50Seconds())) {
            return gate(
                    "latency",
                    "Latency of the labels-only call",
                    "no run timed",
                    NO_RANGE,
                    passLine,
                    Status.NOT_MEASURED);
        }
        String measured = String.format(
                Locale.ROOT, "p50 %.1f s, p95 %.1f s (%d runs)", wait.p50Seconds(), wait.p95Seconds(), wait.runs());
        boolean pass = wait.p50Seconds() <= PassLines.P50_MAX_SECONDS && wait.p95Seconds() <= PassLines.P95_MAX_SECONDS;
        return gate(
                "latency",
                "Latency of the labels-only call",
                measured,
                NO_RANGE,
                passLine,
                pass ? Status.PASS : Status.FAIL);
    }

    /** Cost per analysis: at or below today's mean. With no stored baseline the number is printed and nothing more. */
    public static Gate cost(double meanMicros, OptionalLong baselineMicros) {
        if (Double.isNaN(meanMicros)) {
            return gate(
                    "cost",
                    "Cost per analysis",
                    "no cost measured",
                    NO_RANGE,
                    "at or below today's mean",
                    Status.NOT_MEASURED);
        }
        String measured = String.format(Locale.ROOT, "USD %.4f per analysis", meanMicros / MICROS_PER_DOLLAR);
        if (baselineMicros.isEmpty()) {
            return gate(
                    "cost",
                    "Cost per analysis",
                    measured,
                    NO_RANGE,
                    "at or below today's mean (measure first: no baseline stored yet)",
                    Status.NOT_SET);
        }
        String passLine = String.format(
                Locale.ROOT, "at or below USD %.4f (stored baseline)", baselineMicros.getAsLong() / MICROS_PER_DOLLAR);
        return gate(
                "cost",
                "Cost per analysis",
                measured,
                NO_RANGE,
                passLine,
                meanMicros <= baselineMicros.getAsLong() ? Status.PASS : Status.FAIL);
    }

    private static Gate atLeast(
            String id, String name, double value, String measured, String range, String passLine, double line) {
        Status status = Double.isNaN(value) ? Status.NOT_MEASURED : value >= line ? Status.PASS : Status.FAIL;
        return gate(id, name, measured, range, passLine, status);
    }

    private static Gate atMost(
            String id, String name, double value, String measured, String range, String passLine, double line) {
        Status status = Double.isNaN(value) ? Status.NOT_MEASURED : value <= line ? Status.PASS : Status.FAIL;
        return gate(id, name, measured, range, passLine, status);
    }

    private static Gate gate(String id, String name, String measured, String range, String passLine, Status status) {
        return new Gate(id, name, measured, range, passLine, status, PassLines.SOURCE);
    }

    private static String percent(double share) {
        return Double.isNaN(share) ? "not measured" : String.format(Locale.ROOT, "%.2f %%", share * PERCENT);
    }

    private static String percentRange(Interval range) {
        return range.computable()
                ? String.format(Locale.ROOT, "%.2f %% to %.2f %%", range.low() * PERCENT, range.high() * PERCENT)
                : NO_RANGE;
    }

    private static String wholePercent(double share) {
        return wholeNumber(share * PERCENT) + " %";
    }

    private static String wholeNumber(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }
}
