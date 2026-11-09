package app.stageminute.analysis.internal;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Header-only AAC/M4A files of any length for tests: an {@code ftyp} box and a {@code moov} box holding an {@code
 * mvhd} (version 0) with the length, and no sound. The fake speech to text never reads the sound, so a test can send a
 * 60.7 s take in a few bytes.
 */
public final class M4aFiles {

    private static final int MS_TIMESCALE = 1000;

    private M4aFiles() {}

    public static byte[] ofLength(int durationMs) {
        return withTimescale(MS_TIMESCALE, durationMs);
    }

    static byte[] withTimescale(int timescale, long duration) {
        ByteBuffer mvhd = ByteBuffer.allocate(8 + 100);
        mvhd.putInt(8 + 100).put(ascii("mvhd"));
        mvhd.putInt(0); // version 0, no flags
        mvhd.putInt(0).putInt(0); // created, modified
        mvhd.putInt(timescale).putInt((int) duration);
        ByteBuffer moov = ByteBuffer.allocate(8 + mvhd.capacity());
        moov.putInt(moov.capacity()).put(ascii("moov")).put(mvhd.array());
        ByteBuffer ftyp = ByteBuffer.allocate(16);
        ftyp.putInt(16).put(ascii("ftyp")).put(ascii("M4A ")).putInt(0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ftyp.array());
        out.writeBytes(moov.array());
        return out.toByteArray();
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
