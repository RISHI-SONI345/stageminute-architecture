package app.stageminute.analysis.internal;

import java.util.OptionalInt;

/**
 * Bridge for the trust harness (tests/trust, AD-153): the harness measures a take's length the way the server does
 * (C-69), from the file's own header, so it needs the server's reader. Compiled in the harness's own source set.
 */
public final class TrustAudio {

    private TrustAudio() {}

    /** The length in milliseconds, or empty when the bytes are not a readable M4A file. */
    public static OptionalInt durationMs(byte[] audio) {
        return M4aAudio.durationMs(audio);
    }
}
