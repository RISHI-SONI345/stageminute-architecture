package app.stageminute.trust.labelset;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A tiny SYNTHETIC labelled set and replay folder, written on demand to a folder of the caller's choice: no real voice,
 * no real person, no audio in git. The "audio" is header-only M4A files (a length and no sound), which only the fake
 * vendors can run on; the labels are invented. It exists so the harness is tested end to end in CI and so the founder
 * can see one sample report. Numbers from it prove the harness, never the product.
 */
public final class SyntheticSet {

    private static final int MVHD_SIZE = 108;
    private static final int MS_TIMESCALE = 1000;
    private static final String[] SPEAKERS = {"syn-01", "syn-02", "syn-03"};
    private static final String[] ACCENTS = {"synthetic-a", "synthetic-b", "synthetic-b"};
    private static final String[] CONSENTS = {"SYN-CONSENT-1", "SYN-CONSENT-2", "SYN-CONSENT-3"};
    private static final int[][] TAKE_MS = {{45_000, 70_000}, {50_000, 62_000}, {48_000}};

    private SyntheticSet() {}

    /** Writes {@code root/set} and {@code root/replay}. */
    public static void write(Path root) {
        try {
            writeSet(root.resolve("set"));
            writeReplay(root.resolve("replay"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeSet(Path set) throws IOException {
        Files.createDirectories(set);
        Files.writeString(set.resolve("SYNTHETIC.txt"), "Invented data for testing the harness. No real person.\n");
        Files.writeString(
                set.resolve("consents.json"),
                "{\"SYN-CONSENT-1\":\"active\",\"SYN-CONSENT-2\":\"active\",\"SYN-CONSENT-3\":\"revoked\"}");
        for (int i = 0; i < SPEAKERS.length; i++) {
            Path dir = set.resolve(SPEAKERS[i]);
            Files.createDirectories(dir);
            StringBuilder phone = new StringBuilder();
            StringBuilder first = new StringBuilder();
            StringBuilder second = new StringBuilder();
            for (int take = 0; take < TAKE_MS[i].length; take++) {
                String name = "take-" + (take + 1) + ".m4a";
                Files.write(dir.resolve(name), m4a(TAKE_MS[i][take]));
                String sep = take == 0 ? "" : ",";
                phone.append(sep).append('"').append(name).append("\":").append(TAKE_MS[i][take] - 200);
                first.append(sep).append(takeLabels(name, "[\"strong\",\"strong\",\"weak\",\"strong\"]"));
                second.append(sep).append(takeLabels(name, "[\"strong\",\"weak\",\"weak\",\"strong\"]"));
            }
            Files.writeString(
                    dir.resolve("speaker.json"),
                    "{\"accent_group\":\"" + ACCENTS[i] + "\",\"style\":\"PREP\",\"consent_id\":\"" + CONSENTS[i]
                            + "\",\"annotators\":[\"syn-a1\",\"syn-a2\"],\"synthetic\":true,\"phone_duration_ms\":{"
                            + phone + "}}");
            Files.writeString(dir.resolve("labels-syn-a1.json"), "{\"takes\":{" + first + "}}");
            Files.writeString(dir.resolve("labels-syn-a2.json"), "{\"takes\":{" + second + "}}");
        }
    }

    private static String takeLabels(String name, String steps) {
        return "\"" + name + "\":{\"steps\":" + steps + ",\"fillers_ms\":[8000,16000],"
                + "\"pauses\":[],\"asr_error_spans\":[],\"first_fix\":\"add_example\",\"word_count\":100}";
    }

    private static void writeReplay(Path replay) throws IOException {
        Files.createDirectories(replay);
        Files.writeString(replay.resolve("SYNTHETIC.txt"), "Invented data for testing the harness. No real person.\n");
        Files.writeString(replay.resolve("replay.json"), "{\"style\":\"PREP\"}");
        Files.write(replay.resolve("clip-1.m4a"), m4a(40_000));
        Files.write(replay.resolve("clip-2.m4a"), m4a(60_000));
    }

    /** A header-only M4A of the given length: an ftyp box and a moov box holding an mvhd, and no sound. */
    public static byte[] m4a(int durationMs) {
        ByteBuffer mvhd = ByteBuffer.allocate(MVHD_SIZE);
        mvhd.putInt(MVHD_SIZE).put(ascii("mvhd"));
        mvhd.putInt(0); // version 0, no flags
        mvhd.putInt(0).putInt(0); // created, modified
        mvhd.putInt(MS_TIMESCALE).putInt(durationMs);
        ByteBuffer moov = ByteBuffer.allocate(8 + MVHD_SIZE);
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
