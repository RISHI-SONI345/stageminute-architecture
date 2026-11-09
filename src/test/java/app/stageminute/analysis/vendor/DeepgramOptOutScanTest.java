package app.stageminute.analysis.vendor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Second line of defence for 05 F-080 and 03 F-027: the behaviour tests prove {@code mip_opt_out=true} on every call
 * that exists today; this one proves that no second way to reach Deepgram can appear without the opt-out. The address
 * is built in {@code DeepgramRequest} alone, and the client sends it through one call site.
 */
class DeepgramOptOutScanTest {

    private static final Path SOURCES = Path.of("src/main/java");

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> all = Files.walk(SOURCES)) {
            return all.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void onlyTheRequestBuilderKnowsTheDeepgramPathAndTheOptOutIsFixedTextThere() throws IOException {
        List<Path> withPath = javaFiles().stream()
                .filter(path -> read(path).contains("/v1/listen"))
                .toList();
        List<Path> withHost = javaFiles().stream()
                .filter(path -> read(path).contains("deepgram.com"))
                .toList();

        assertThat(withPath).extracting(path -> path.getFileName().toString()).containsExactly("DeepgramRequest.java");
        // the host appears only as the configured default address, which VendorEndpoint hands to the one builder
        assertThat(withHost)
                .extracting(path -> path.getFileName().toString())
                .containsExactlyInAnyOrder("VendorConfig.java");
        assertThat(read(SOURCES.resolve("app/stageminute/analysis/vendor/DeepgramRequest.java")))
                .contains("\"&mip_opt_out=true\"")
                .contains("\"&filler_words=true\"");
    }

    @Test
    void theClientBuildsNoAddressOfItsOwnAndSendsThroughOneCallSite() throws IOException {
        String client = read(SOURCES.resolve("app/stageminute/analysis/vendor/DeepgramClient.java"));

        assertThat(client).doesNotContain("URI.create").doesNotContain("\"/v1/listen\"");
        assertThat(count(client, "transport.post(")).isEqualTo(1);
        assertThat(count(client, "DeepgramRequest.uri(")).isEqualTo(1);
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            count++;
        }
        return count;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path.getFileName(), e);
        }
    }
}
