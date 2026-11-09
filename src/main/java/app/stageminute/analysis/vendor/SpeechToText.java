package app.stageminute.analysis.vendor;

/**
 * The speech-to-text port (02-SERVER-LLD.md, analysis): Deepgram in production (#19), {@link FakeSpeechToText} on a
 * development machine, {@link VendorConfig} picks one and fails closed. The audio lives in memory for this one call
 * (rule 4); the answer is speech and is never stored or logged.
 */
public interface SpeechToText {

    /**
     * Turns one take into timed words and sentences.
     *
     * @param audio the AAC/M4A bytes of the take
     * @param durationMs the length the server measured from the audio (C-69); the transcript uses it as its length
     * @param request the options sent with the call; the pipeline always passes {@link SttRequest#launch()}
     * @throws VendorUnavailable when the vendor cannot answer (network, time-out, a bad answer)
     */
    SpeechResult transcribe(byte[] audio, int durationMs, SttRequest request);
}
