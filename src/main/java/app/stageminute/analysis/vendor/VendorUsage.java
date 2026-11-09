package app.stageminute.analysis.vendor;

import java.util.Objects;

/**
 * What one real vendor call used, as the vendor reported it (#19.1, for the spend meter of #19.2). Units only: nothing
 * here is a price and nothing holds speech. Deepgram bills audio time; Gemini bills tokens.
 *
 * @param vendor which vendor was called
 * @param audioMs milliseconds of audio the vendor says it processed (Deepgram), else 0
 * @param inputTokens prompt tokens (Gemini), else 0
 * @param outputTokens answer tokens including thinking tokens (Gemini), else 0
 */
public record VendorUsage(Vendor vendor, long audioMs, long inputTokens, long outputTokens) {

    public VendorUsage {
        Objects.requireNonNull(vendor, "vendor");
        if (audioMs < 0 || inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("usage cannot be negative");
        }
    }

    /** Audio time Deepgram processed. */
    public static VendorUsage audio(long audioMs) {
        return new VendorUsage(Vendor.DEEPGRAM, audioMs, 0, 0);
    }

    /** Tokens Gemini read and wrote. */
    public static VendorUsage tokens(long inputTokens, long outputTokens) {
        return new VendorUsage(Vendor.GEMINI, 0, inputTokens, outputTokens);
    }
}
