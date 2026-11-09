package app.stageminute.trust.run;

import app.stageminute.trust.labelset.LabelSetException;
import app.stageminute.trust.model.SavedRun;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The deletion path of F-123 as one command: "a speaker asks to be removed". It removes the speaker's folder (the
 * recordings and the annotators' labels), marks the speaker's consent {@code deleted} in {@code consents.json} so a
 * restored folder would still be left out, takes the speaker's rows out of every saved run, and takes the speaker's
 * lines out of every Markdown report. The speaker id must be a plain folder name: a path is refused.
 */
public final class SpeakerDeletion {

    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private SpeakerDeletion() {}

    /**
     * What was removed.
     *
     * @param filesRemoved files deleted from the speaker's folder
     * @param runRowsRemoved rows taken out of saved runs
     * @param reportLinesRemoved lines taken out of Markdown reports
     * @param consentMarked whether a consent entry was marked deleted
     */
    public record Summary(int filesRemoved, int runRowsRemoved, int reportLinesRemoved, boolean consentMarked) {}

    public static Summary delete(Path setRoot, Path reportsDir, String speakerId) {
        if (!PLAIN_NAME.matcher(speakerId).matches() || speakerId.contains("..")) {
            throw new LabelSetException("a speaker id is a plain folder name, not a path");
        }
        Path folder = setRoot.resolve(speakerId).normalize();
        if (!folder.getParent().equals(setRoot.normalize()) || !Files.isDirectory(folder)) {
            throw new LabelSetException("there is no speaker folder with that id in the set");
        }
        boolean consent = markConsentDeleted(setRoot, folder);
        int files = removeTree(folder);
        int rows = 0;
        int lines = 0;
        if (Files.isDirectory(reportsDir)) {
            for (Path file : list(reportsDir)) {
                String name = file.getFileName().toString();
                if (name.startsWith("run-") && name.endsWith(".json")) {
                    rows += cleanRun(file, speakerId);
                } else if (name.endsWith(".md")) {
                    lines += cleanReport(file, speakerId);
                }
            }
        }
        return new Summary(files, rows, lines, consent);
    }

    private static boolean markConsentDeleted(Path setRoot, Path folder) {
        Path consents = setRoot.resolve("consents.json");
        try {
            String consentId = consentId(folder.resolve("speaker.json"));
            if (consentId == null || !Files.isRegularFile(consents)) {
                return false;
            }
            String text = Files.readString(consents);
            String marked =
                    text.replaceAll("(\"" + Pattern.quote(consentId) + "\"\\s*:\\s*)\"[^\"]*\"", "$1\"deleted\"");
            Files.writeString(consents, marked);
            return !marked.equals(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String consentId(Path speakerFile) throws IOException {
        if (!Files.isRegularFile(speakerFile)) {
            return null;
        }
        var matcher = Pattern.compile("\"consent_id\"\\s*:\\s*\"([^\"]+)\"").matcher(Files.readString(speakerFile));
        return matcher.find() ? matcher.group(1) : null;
    }

    private static int removeTree(Path folder) {
        int count = 0;
        try (Stream<Path> walk = Files.walk(folder)) {
            List<Path> paths = walk.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                if (Files.isRegularFile(path)) {
                    count++;
                }
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return count;
    }

    private static int cleanRun(Path file, String speakerId) {
        SavedRun run = RunStore.read(file);
        List<SavedRun.Row> kept = new ArrayList<>();
        for (SavedRun.Row row : run.rows()) {
            if (!row.key().startsWith(speakerId + "/")) {
                kept.add(row);
            }
        }
        int removed = run.rows().size() - kept.size();
        if (removed > 0) {
            RunStore.write(file, new SavedRun(run.meta(), kept));
        }
        return removed;
    }

    /** Per-speaker lines of the report start with the anonymous id in backticks as the first table cell. */
    private static int cleanReport(Path file, String speakerId) {
        try {
            List<String> kept = new ArrayList<>();
            int removed = 0;
            String marker = "| `" + speakerId + "` |";
            for (String line : Files.readAllLines(file)) {
                if (line.startsWith(marker)) {
                    removed++;
                } else {
                    kept.add(line);
                }
            }
            if (removed > 0) {
                Files.write(file, kept);
            }
            return removed;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> list(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
