package app.stageminute.trust.labelset;

import app.stageminute.analysis.score.StepStatus;
import app.stageminute.analysis.score.Style;
import app.stageminute.trust.model.PausePoint;
import app.stageminute.trust.model.Reference;
import app.stageminute.trust.model.Span;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the labelled set and the replay clips from their folders (format: {@code tests/trust/README.md}).
 *
 * <pre>
 * set/
 *   consents.json                   {"C-001": "active", "C-002": "revoked"}
 *   speaker-01/
 *     speaker.json                  accent_group, style, consent_id, annotators[], topic?, cap_ms?, phone_duration_ms{}
 *     labels-a1.json, labels-a2.json   one per annotator
 *     take-1.m4a ...
 * </pre>
 */
public final class LabelSetLoader {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MIN_ANNOTATORS = 2;
    private static final int STEPS = 4;
    private static final int PREP_CAP_MS = 60_000;
    private static final int STAR_CAP_MS = 90_000;
    private static final String DEFAULT_TOPIC = "Tell us about something you learned recently.";
    private static final String AUDIO = ".m4a";

    private LabelSetLoader() {}

    public static LabelSet load(Path root) {
        if (!Files.isDirectory(root)) {
            throw new LabelSetException("The labelled set folder does not exist: " + root.getFileName());
        }
        Path consentsFile = root.resolve("consents.json");
        if (!Files.isRegularFile(consentsFile)) {
            throw new LabelSetException("The labelled set has no consents.json (F-123: every speaker needs a consent)");
        }
        Map<String, JsonNode> consents = new HashMap<>();
        read(consentsFile).properties().forEach(entry -> consents.put(entry.getKey(), entry.getValue()));
        List<LabelSet.SpeakerFolder> speakers = new ArrayList<>();
        List<LabelSet.Excluded> excluded = new ArrayList<>();
        for (Path dir : subfolders(root)) {
            JsonNode speaker = read(requireFile(dir, "speaker.json"));
            String id = dir.getFileName().toString();
            String consentId = text(speaker, "consent_id", id + "/speaker.json");
            JsonNode consent = consents.get(consentId);
            if (consent == null) {
                excluded.add(new LabelSet.Excluded(id, "consent missing"));
            } else if (!"active".equals(consent.asString(""))) {
                excluded.add(new LabelSet.Excluded(id, "consent " + consent.asString("not active")));
            } else {
                speakers.add(speaker(dir, id, speaker, consentId));
            }
        }
        if (speakers.isEmpty() && excluded.isEmpty()) {
            throw new LabelSetException("The labelled set has no speaker folders");
        }
        return new LabelSet(speakers, excluded);
    }

    public static List<LabelSet.ClipInput> loadReplay(Path root) {
        if (!Files.isDirectory(root)) {
            throw new LabelSetException("The replay folder does not exist: " + root.getFileName());
        }
        Path settingsFile = root.resolve("replay.json");
        JsonNode settings = Files.isRegularFile(settingsFile) ? read(settingsFile) : JSON.createObjectNode();
        Style style = style(settings.path("style").asString("PREP"), "replay.json");
        String topic = settings.path("topic").asString(DEFAULT_TOPIC);
        int cap = settings.path("cap_ms").asInt(defaultCap(style));
        List<LabelSet.ClipInput> clips = new ArrayList<>();
        for (Path audio : audioFiles(root)) {
            clips.add(new LabelSet.ClipInput(audio.getFileName().toString(), audio, style, topic, cap));
        }
        if (clips.isEmpty()) {
            throw new LabelSetException("The replay folder has no " + AUDIO + " clips");
        }
        return clips;
    }

    private static LabelSet.SpeakerFolder speaker(Path dir, String id, JsonNode speaker, String consentId) {
        String where = id + "/speaker.json";
        Style style = style(text(speaker, "style", where), where);
        List<String> annotators = new ArrayList<>();
        speaker.path("annotators").forEach(node -> annotators.add(node.asString("")));
        if (annotators.size() < MIN_ANNOTATORS || annotators.stream().anyMatch(String::isBlank)) {
            throw new LabelSetException(where + " needs at least " + MIN_ANNOTATORS + " annotator ids (F-123)");
        }
        Map<String, JsonNode> labelFiles = new LinkedHashMap<>();
        for (String annotator : annotators) {
            labelFiles.put(annotator, read(requireFile(dir, "labels-" + annotator + ".json")));
        }
        JsonNode phone = speaker.path("phone_duration_ms");
        List<LabelSet.TakeInput> takes = new ArrayList<>();
        for (Path audio : audioFiles(dir)) {
            String name = audio.getFileName().toString();
            List<Reference> references = new ArrayList<>();
            for (Map.Entry<String, JsonNode> file : labelFiles.entrySet()) {
                JsonNode labels = file.getValue().path("takes").path(name);
                if (labels.isMissingNode()) {
                    throw new LabelSetException("annotator " + file.getKey() + " has no labels for " + id + "/" + name);
                }
                references.add(reference(file.getKey(), labels, id + "/" + name));
            }
            OptionalInt phoneMs =
                    phone.has(name) ? OptionalInt.of(phone.path(name).asInt()) : OptionalInt.empty();
            takes.add(new LabelSet.TakeInput(name, audio, phoneMs, references));
        }
        if (takes.isEmpty()) {
            throw new LabelSetException(id + " has no " + AUDIO + " takes");
        }
        return new LabelSet.SpeakerFolder(
                id,
                dir,
                text(speaker, "accent_group", where),
                style,
                speaker.path("topic").asString(DEFAULT_TOPIC),
                speaker.path("cap_ms").asInt(defaultCap(style)),
                consentId,
                annotators,
                takes);
    }

    private static Reference reference(String annotator, JsonNode labels, String where) {
        List<StepStatus> steps = new ArrayList<>();
        labels.path("steps").forEach(node -> steps.add(step(node.asString(""), where)));
        if (steps.size() != STEPS) {
            throw new LabelSetException(where + " (annotator " + annotator + ") needs " + STEPS + " step labels");
        }
        List<Integer> fillers = new ArrayList<>();
        labels.path("fillers_ms").forEach(node -> fillers.add(node.asInt()));
        List<PausePoint> pauses = new ArrayList<>();
        labels.path("pauses")
                .forEach(node -> pauses.add(new PausePoint(
                        node.path("start_ms").asInt(), node.path("length_ms").asInt())));
        List<Span> spans = new ArrayList<>();
        labels.path("asr_error_spans")
                .forEach(node -> spans.add(new Span(
                        node.path("start_ms").asInt(), node.path("end_ms").asInt())));
        String fix = labels.path("first_fix").asString("");
        OptionalInt words = labels.has("word_count")
                ? OptionalInt.of(labels.path("word_count").asInt())
                : OptionalInt.empty();
        return new Reference(
                annotator, fillers, pauses, steps, spans, fix.isBlank() ? Optional.empty() : Optional.of(fix), words);
    }

    private static StepStatus step(String word, String where) {
        try {
            return StepStatus.valueOf(word.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new LabelSetException(where + " has a step label that is not strong, weak or missing");
        }
    }

    private static Style style(String word, String where) {
        try {
            return Style.valueOf(word.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new LabelSetException(where + " has a style that is not PREP or STAR");
        }
    }

    private static int defaultCap(Style style) {
        return style == Style.STAR ? STAR_CAP_MS : PREP_CAP_MS;
    }

    private static String text(JsonNode node, String field, String where) {
        String value = node.path(field).asString("");
        if (value.isBlank()) {
            throw new LabelSetException(where + " needs " + field);
        }
        return value;
    }

    private static Path requireFile(Path dir, String name) {
        Path file = dir.resolve(name);
        if (!Files.isRegularFile(file)) {
            throw new LabelSetException(dir.getFileName() + " has no " + name);
        }
        return file;
    }

    private static JsonNode read(Path file) {
        try {
            return JSON.readTree(Files.readString(file));
        } catch (IOException | RuntimeException e) {
            throw new LabelSetException(file.getFileName() + " is not readable JSON");
        }
    }

    private static List<Path> subfolders(Path root) {
        try (Stream<Path> list = Files.list(root)) {
            return list.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            throw new LabelSetException("The folder cannot be listed: " + root.getFileName());
        }
    }

    private static List<Path> audioFiles(Path dir) {
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(path -> path.getFileName().toString().endsWith(AUDIO))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new LabelSetException("The folder cannot be listed: " + dir.getFileName());
        }
    }
}
