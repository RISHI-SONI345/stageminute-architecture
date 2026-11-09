package app.stageminute.trust.measures;

import app.stageminute.analysis.spend.SpendPrices;
import app.stageminute.analysis.vendor.Vendor;
import app.stageminute.analysis.vendor.VendorUsage;
import java.util.Locale;

/**
 * The dollar estimate printed before a full run, which needs the founder's yes (it sends audio to outside companies and
 * costs money). Deepgram is billed by audio length, so the repeat-run test multiplies the clips' audio by the runs.
 * Gemini's expected cost uses the one figure F-111 gives, about 1,100 tokens a call, at the output price (the higher
 * one, so the estimate leans high); the worst case uses the reserve sizes of the spend meter. Prices come from the
 * server settings passed in as {@link SpendPrices}.
 *
 * @param takeCalls pipeline runs (each is one speech-to-text call and one judge call)
 * @param audioMinutes audio minutes sent to speech to text
 * @param expectedMicros the expected cost in millionths of a dollar
 * @param worstCaseMicros the worst case in millionths of a dollar
 * @param runs how many times each clip is run
 */
public record CostEstimate(int takeCalls, double audioMinutes, long expectedMicros, long worstCaseMicros, int runs) {

    private static final long GEMINI_TOKENS_PER_CALL = 1_100;
    private static final double MS_PER_MINUTE = 60_000.0;
    private static final double MICROS_PER_DOLLAR = 1_000_000.0;

    public static CostEstimate of(
            long labelledAudioMs, int labelledTakes, long clipAudioMs, int clips, int runs, SpendPrices prices) {
        long audioMs = labelledAudioMs + clipAudioMs * runs;
        int calls = labelledTakes + clips * runs;
        long deepgram = prices.cost(VendorUsage.audio(audioMs));
        long geminiExpected = prices.cost(VendorUsage.tokens(0, GEMINI_TOKENS_PER_CALL)) * calls;
        long geminiWorst = prices.worstCase(Vendor.GEMINI, 0) * calls;
        return new CostEstimate(
                calls, audioMs / MS_PER_MINUTE, deepgram + geminiExpected, deepgram + geminiWorst, runs);
    }

    /** The plain text printed before a run. */
    public String describe() {
        return String.format(
                Locale.ROOT,
                "ESTIMATE: %d pipeline runs, %.1f audio minutes (the repeat-run test runs each clip %d times, so its"
                        + " audio and calls count %d times). Expected cost USD %.4f, worst case USD %.4f.",
                takeCalls,
                audioMinutes,
                runs,
                runs,
                expectedMicros / MICROS_PER_DOLLAR,
                worstCaseMicros / MICROS_PER_DOLLAR);
    }
}
