package app.stageminute.trust.run;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The stored "today's mean" cost per analysis (11 section 1: at or below today's mean, measure first, F-111). A small
 * file, {@code tests/trust/cost-baseline.json}, holding one number of millionths of a dollar and the date: no personal
 * data, so the founder may commit it. Until it exists the harness prints the cost and gives no pass or fail.
 */
public final class CostBaseline {

    private static final Pattern MEAN = Pattern.compile("\"mean_micros\"\\s*:\\s*(\\d+)");

    private CostBaseline() {}

    public static OptionalLong read(Path file) {
        if (!Files.isRegularFile(file)) {
            return OptionalLong.empty();
        }
        try {
            Matcher matcher = MEAN.matcher(Files.readString(file));
            return matcher.find() ? OptionalLong.of(Long.parseLong(matcher.group(1))) : OptionalLong.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void write(Path file, long meanMicros, String date) {
        try {
            Files.writeString(file, "{\"mean_micros\": " + meanMicros + ", \"date\": \"" + date + "\"}\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
