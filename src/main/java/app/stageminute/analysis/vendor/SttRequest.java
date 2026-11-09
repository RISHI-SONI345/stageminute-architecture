package app.stageminute.analysis.vendor;

/**
 * The options of a speech-to-text call that the requirements fix (03 F-027, F-031, F-080). Every call opts out of the
 * vendor's model improvement programme ({@code mip_opt_out=true}) and asks for filler sounds to be kept ({@code
 * filler_words=true}); a test fails when either is missing (inherited by #19 for the real Deepgram client).
 *
 * @param mipOptOut Deepgram's {@code mip_opt_out}: the audio is not used to improve their models
 * @param fillerWords Deepgram's {@code filler_words}: "uh" and "um" are kept in the transcript
 */
public record SttRequest(boolean mipOptOut, boolean fillerWords) {

    /** The only options the pipeline sends. */
    public static SttRequest launch() {
        return new SttRequest(true, true);
    }
}
