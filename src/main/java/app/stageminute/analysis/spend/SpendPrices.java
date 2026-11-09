package app.stageminute.analysis.spend;

import app.stageminute.analysis.vendor.Vendor;
import app.stageminute.analysis.vendor.VendorUsage;

/**
 * What the vendors charge, in millionths of a dollar ("micros"), and the one place where units become cost (#19.2). The
 * prices and the worst-case sizes are settings passed in; nothing here is a price of its own. Every division rounds up,
 * so the meter never counts less than the bill. Pure: no clock, no database.
 *
 * @param deepgramMicrosPerAudioMinute Deepgram's price for a minute of audio
 * @param geminiInputMicrosPerMillion Gemini's price for a million input tokens
 * @param geminiOutputMicrosPerMillion Gemini's price for a million output tokens (thinking tokens included)
 * @param geminiWorstInputTokens the most input tokens one call can hold, for the reserve
 * @param geminiWorstOutputTokens the most output tokens one call can write, for the reserve
 */
public record SpendPrices(
        long deepgramMicrosPerAudioMinute,
        long geminiInputMicrosPerMillion,
        long geminiOutputMicrosPerMillion,
        long geminiWorstInputTokens,
        long geminiWorstOutputTokens) {

    private static final long MS_PER_MINUTE = 60_000L;
    private static final long PER_MILLION = 1_000_000L;

    /** The cost of what the vendor reported it used. */
    public long cost(VendorUsage usage) {
        return switch (usage.vendor()) {
            case DEEPGRAM -> audio(usage.audioMs());
            case GEMINI -> tokens(usage.inputTokens(), usage.outputTokens());
        };
    }

    /**
     * The most one call can cost, held back before the call (a Deepgram call bills the length of the take; a Gemini
     * call is bounded by the token ceilings the client sets).
     */
    public long worstCase(Vendor vendor, long audioMs) {
        return switch (vendor) {
            case DEEPGRAM -> audio(audioMs);
            case GEMINI -> tokens(geminiWorstInputTokens, geminiWorstOutputTokens);
        };
    }

    /** False when any number is zero or less: a price of nothing would make the day look free, so the meter pauses. */
    public boolean valid() {
        return deepgramMicrosPerAudioMinute > 0
                && geminiInputMicrosPerMillion > 0
                && geminiOutputMicrosPerMillion > 0
                && geminiWorstInputTokens > 0
                && geminiWorstOutputTokens > 0;
    }

    private long audio(long audioMs) {
        return Math.ceilDiv(audioMs * deepgramMicrosPerAudioMinute, MS_PER_MINUTE);
    }

    private long tokens(long input, long output) {
        return Math.ceilDiv(input * geminiInputMicrosPerMillion, PER_MILLION)
                + Math.ceilDiv(output * geminiOutputMicrosPerMillion, PER_MILLION);
    }
}
