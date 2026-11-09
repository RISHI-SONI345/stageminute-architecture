package app.stageminute.trust.run;

import app.stageminute.trust.model.Observation;
import app.stageminute.trust.model.SavedRun;
import app.stageminute.trust.model.TakeCase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;

/**
 * Saves each run next to its report as a JSON file ({@code run-<date>-<mode>.json}) and finds the last one for the
 * per-take diff. The folder is git-ignored: rows carry an anonymous key and labels, never words, but they are still one
 * person's results per row.
 */
public final class RunStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PREFIX = "run-";
    private static final String SUFFIX = ".json";

    private RunStore() {}

    /** One row per labelled take and per clip (a clip's first run). */
    public static SavedRun toSaved(SavedRun.Meta meta, List<TakeCase> labelled, List<TakeCase> replay) {
        List<SavedRun.Row> rows = new ArrayList<>();
        for (TakeCase take : labelled) {
            rows.add(row(take));
        }
        for (TakeCase clip : replay) {
            rows.add(row(clip));
        }
        return new SavedRun(meta, rows);
    }

    private static SavedRun.Row row(TakeCase take) {
        Observation run = take.runs().isEmpty() ? null : take.runs().get(0);
        String key = take.speakerId() + "/" + take.takeName();
        if (run == null || !run.scored()) {
            return new SavedRun.Row(key, false, 0, List.of(), "");
        }
        return new SavedRun.Row(
                key,
                true,
                run.total(),
                run.steps().stream()
                        .map(step -> step.name().toLowerCase(Locale.ROOT))
                        .toList(),
                run.fixCode().orElse(""));
    }

    /** The newest saved run of this mode, read before the new one is written. */
    public static Optional<SavedRun> latest(Path folder, String mode) {
        if (!Files.isDirectory(folder)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(path -> path.getFileName().toString().startsWith(PREFIX)
                            && path.getFileName().toString().endsWith("-" + mode + SUFFIX))
                    .sorted()
                    .reduce((first, second) -> second)
                    .map(RunStore::read);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes the run and returns the file name. */
    public static String save(Path folder, SavedRun run) {
        try {
            Files.createDirectories(folder);
            String name = PREFIX + run.meta().date().replace(':', '-') + "-"
                    + run.meta().mode() + SUFFIX;
            Files.writeString(
                    folder.resolve(name), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(run));
            return name;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static SavedRun read(Path file) {
        try {
            return JSON.readValue(Files.readString(file), SavedRun.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void write(Path file, SavedRun run) {
        try {
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(run));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
