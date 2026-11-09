package app.stageminute.analysis.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The server measures the length of a take from the audio itself (C-69, rule 1: the phone's {@code duration_ms} is a
 * hint), and refuses what is not an AAC/M4A file (415). The two real files were made with ffmpeg as the phone's
 * recorder makes them (AAC-LC, mono, 44.1 kHz, 64 kbit/s, 2.5 s), one with the length box at the end of the file
 * (ffmpeg's default) and one at the start.
 */
class M4aAudioTest {

    @Test
    void aRealFileWithItsLengthBoxAtTheEndIsMeasured() throws IOException {
        assertThat(M4aAudio.durationMs(resource("tone-2500ms-moov-at-end.m4a")).orElse(-1))
                .isBetween(2450, 2600);
    }

    @Test
    void aRealFileWithItsLengthBoxFirstIsMeasured() throws IOException {
        assertThat(M4aAudio.durationMs(resource("tone-2500ms-moov-first.m4a")).orElse(-1))
                .isBetween(2450, 2600);
    }

    @Test
    void aHeaderOnlyFileGivesItsLength() {
        assertThat(M4aAudio.durationMs(M4aFiles.ofLength(60_700))).hasValue(60_700);
        assertThat(M4aAudio.durationMs(M4aFiles.ofLength(55_000))).hasValue(55_000);
    }

    @Test
    void somethingThatIsNotAnMp4FileIsRefused() {
        assertThat(M4aAudio.durationMs("RIFF....WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                .isEmpty();
        assertThat(M4aAudio.durationMs(new byte[0])).isEmpty();
        assertThat(M4aAudio.durationMs(new byte[] {0, 0, 0, 8})).isEmpty();
    }

    @Test
    void aCutOffFileIsRefused() throws IOException {
        byte[] whole = resource("tone-2500ms-moov-at-end.m4a");
        assertThat(M4aAudio.durationMs(Arrays.copyOf(whole, whole.length - 200)))
                .isEmpty();
    }

    @Test
    void aLengthBoxWithNoTimeScaleIsRefused() {
        assertThat(M4aAudio.durationMs(M4aFiles.withTimescale(0, 1000))).isEmpty();
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(M4aAudioTest.class.getResourceAsStream("/audio/" + name))) {
            return in.readAllBytes();
        }
    }
}
