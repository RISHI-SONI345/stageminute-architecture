package app.stageminute.analysis.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;

/**
 * Reads the length of an AAC/M4A take from its own header (C-69: the server measures the length; the phone's {@code
 * duration_ms} is a hint). An MP4 file is a list of boxes, each "size, type, body"; the length is in {@code
 * moov/mvhd} as a duration over a time scale. Only the boxes are walked: the sound is never decoded or kept. Anything
 * that is not such a file (no {@code ftyp} first, a box that runs past the end, no {@code mvhd}, a time scale of 0)
 * gives empty, which the endpoint answers with 415 {@code unsupported_format}.
 */
final class M4aAudio {

    private static final int HEADER = 8;
    private static final int LARGE_HEADER = 16;
    private static final long MS_PER_SECOND = 1000L;

    private M4aAudio() {}

    /** The length in milliseconds, or empty when the bytes are not a readable M4A file. */
    static OptionalInt durationMs(byte[] audio) {
        ByteBuffer file = ByteBuffer.wrap(audio);
        if (audio.length < HEADER || !"ftyp".equals(type(file, 0))) {
            return OptionalInt.empty();
        }
        int moov = find(file, 0, audio.length, "moov");
        if (moov < 0) {
            return OptionalInt.empty();
        }
        int mvhd = find(file, bodyStart(file, moov), boxEnd(file, moov), "mvhd");
        if (mvhd < 0) {
            return OptionalInt.empty();
        }
        return mvhdDuration(file, bodyStart(file, mvhd), boxEnd(file, mvhd));
    }

    /** The position of the first box of this type between {@code from} and {@code to}, or -1. */
    private static int find(ByteBuffer file, int from, int to, String wanted) {
        int position = from;
        while (position + HEADER <= to) {
            long size = size(file, position);
            if (size < HEADER || position + size > to) {
                return -1; // a box that runs past its parent: a cut-off or broken file
            }
            if (wanted.equals(type(file, position))) {
                return position;
            }
            position += (int) size;
        }
        return -1;
    }

    private static OptionalInt mvhdDuration(ByteBuffer file, int body, int end) {
        if (body + 4 > end) {
            return OptionalInt.empty();
        }
        int version = file.get(body) & 0xff;
        long timescale;
        long duration;
        if (version == 1) {
            if (body + 4 + 8 + 8 + 4 + 8 > end) {
                return OptionalInt.empty();
            }
            timescale = Integer.toUnsignedLong(file.getInt(body + 4 + 16));
            duration = file.getLong(body + 4 + 16 + 4);
        } else {
            if (body + 4 + 4 + 4 + 4 + 4 > end) {
                return OptionalInt.empty();
            }
            timescale = Integer.toUnsignedLong(file.getInt(body + 4 + 8));
            duration = Integer.toUnsignedLong(file.getInt(body + 4 + 8 + 4));
        }
        if (timescale == 0 || duration < 0) {
            return OptionalInt.empty();
        }
        long ms = duration * MS_PER_SECOND / timescale;
        return ms > Integer.MAX_VALUE ? OptionalInt.empty() : OptionalInt.of((int) ms);
    }

    /** A box's size: the 32-bit field, or the 64-bit one when that field is 1, or "to the end" when it is 0. */
    private static long size(ByteBuffer file, int position) {
        long size = Integer.toUnsignedLong(file.getInt(position));
        if (size == 1) {
            return position + LARGE_HEADER <= file.limit() ? file.getLong(position + HEADER) : -1;
        }
        return size == 0 ? file.limit() - position : size;
    }

    private static int bodyStart(ByteBuffer file, int position) {
        return position + (Integer.toUnsignedLong(file.getInt(position)) == 1 ? LARGE_HEADER : HEADER);
    }

    private static int boxEnd(ByteBuffer file, int position) {
        return (int) (position + size(file, position));
    }

    private static String type(ByteBuffer file, int position) {
        byte[] type = new byte[4];
        file.get(position + 4, type);
        return new String(type, StandardCharsets.US_ASCII);
    }
}
