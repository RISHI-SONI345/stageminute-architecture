package app.stageminute.topics.internal;

import app.stageminute.topics.Level;
import app.stageminute.topics.Style;
import app.stageminute.topics.Topic;
import app.stageminute.topics.TopicCatalog;
import app.stageminute.topics.TopicSettings;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and checks the topic files at startup (F-014, F-109). The rules are the ones of the Node check in
 * {@code content/tools/lib/topics.mjs}, and the schema is the same file ({@code content/schema/topic.schema.json}),
 * so a file that fails there fails here. Any problem stops the server from starting.
 */
public final class TopicFileLoader {

    /** One topic file: its name (for messages) and its text. */
    public record SourceFile(String name, String json) {}

    private static final Logger log = LoggerFactory.getLogger(TopicFileLoader.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Level LOWEST_LEVEL = Level.STARTER;
    private static final Map<Level, String> LEVEL_CODE = Map.of(
            Level.STARTER, "sta",
            Level.ELEMENTARY, "ele",
            Level.INTERMEDIATE, "int",
            Level.UPPER_INTERMEDIATE, "upp");

    private TopicFileLoader() {}

    /**
     * Checks the files and builds the catalog.
     *
     * @param idsJson content/topics/ids.json: an array with every id ever shipped
     * @param schemaJson content/schema/topic.schema.json
     * @param enforceMin {@code TOPICS_ENFORCE_MIN=1}: a style and level under {@code settings.minPerCell()} live
     *     topics fails; otherwise it is only logged
     * @throws TopicFilesInvalidException with every problem found
     */
    public static TopicCatalog load(
            List<SourceFile> files, String idsJson, String schemaJson, TopicSettings settings, boolean enforceMin) {
        List<String> problems = new ArrayList<>();
        Schema schema =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7).getSchema(JSON.readTree(schemaJson));
        Set<String> listedIds = readIds(idsJson, problems);

        Map<String, String> seenId = new HashMap<>();
        Map<String, String> seenText = new HashMap<>();
        List<Topic> topics = new ArrayList<>();
        for (SourceFile file : files) {
            JsonNode array = readArray(file, problems);
            if (array == null) {
                continue;
            }
            for (int i = 0; i < array.size(); i++) {
                JsonNode node = array.get(i);
                String where = file.name() + "[" + i + "]";
                if (node.path("id").isString()) {
                    String id = node.get("id").asString();
                    String earlier = seenId.putIfAbsent(id, where);
                    if (earlier != null) {
                        problems.add("E-ID-DUPLICATE " + where + " id " + id + " is already used at " + earlier);
                    }
                }
                List<Error> errors = schema.validate(node).stream()
                        .filter(e -> !"if".equals(e.getKeyword()))
                        .toList();
                for (Error error : errors) {
                    problems.add(codeFor(error) + " " + where + " " + error.getMessage());
                }
                if (errors.isEmpty()) {
                    Topic topic = toTopic(node);
                    checkAcrossFiles(topic, where, seenText, problems);
                    topics.add(topic);
                }
            }
        }

        for (String id : listedIds) {
            if (!seenId.containsKey(id)) {
                problems.add("E-ID-REMOVED ids.json id " + id
                        + " is on the list but in no topic file (ids never disappear; retire it instead)");
            }
        }
        seenId.forEach((id, where) -> {
            if (!listedIds.contains(id)) {
                problems.add("E-ID-UNLISTED " + where + " id " + id + " is not on ids.json yet");
            }
        });
        checkSeeds(topics, problems);
        checkCells(topics, settings, enforceMin, problems);

        if (!problems.isEmpty()) {
            throw new TopicFilesInvalidException(problems);
        }
        return TopicCatalog.of(topics, settings);
    }

    private static Set<String> readIds(String idsJson, List<String> problems) {
        Set<String> ids = new HashSet<>();
        try {
            JsonNode array = JSON.readTree(idsJson);
            if (!array.isArray() || array.valueStream().anyMatch(n -> !n.isString())) {
                // copy-ok: startup log line for the developer, never shown to a user
                problems.add("E-FILE ids.json holds an array of id strings");
            } else {
                array.forEach(n -> ids.add(n.asString()));
            }
        } catch (JacksonException e) {
            // copy-ok: startup log line for the developer, never shown to a user
            problems.add("E-FILE ids.json is not valid JSON");
        }
        return ids;
    }

    private static JsonNode readArray(SourceFile file, List<String> problems) {
        try {
            JsonNode array = JSON.readTree(file.json());
            if (!array.isArray()) {
                problems.add("E-FILE " + file.name() + " a topic file holds an array of topics");
                return null;
            }
            return array;
        } catch (JacksonException e) {
            problems.add("E-FILE " + file.name() + " is not valid JSON");
            return null;
        }
    }

    /** The same codes as the Node check, so a founder reads one list. */
    private static String codeFor(Error error) {
        String location = error.getInstanceLocation().toString();
        // The path of the failing field, as "$.text" or "/text": its last part is the field name.
        String field = location.substring(Math.max(location.lastIndexOf('.'), location.lastIndexOf('/')) + 1);
        String keyword = error.getKeyword();
        String missing = error.getProperty() == null ? "" : error.getProperty();
        if ("maxLength".equals(keyword) && "text".equals(field)) {
            return "E-TEXT-TOO-LONG";
        }
        if (("minimum".equals(keyword) || "maximum".equals(keyword))
                && ("think_s".equals(field) || "speak_s".equals(field))) {
            return "E-SECONDS-RANGE";
        }
        if ("required".equals(keyword) && "role_context".equals(missing)) {
            return "E-STAR-NEEDS-ROLE-CONTEXT";
        }
        if ("required".equals(keyword) && "source".equals(missing)) {
            return "E-SOURCE-MISSING";
        }
        return "E-SCHEMA";
    }

    private static Topic toTopic(JsonNode node) {
        return new Topic(
                node.get("id").asString(),
                Style.fromWire(node.get("style").asString()).orElseThrow(),
                Level.fromWire(node.get("level").asString()).orElseThrow(),
                node.get("difficulty").asInt(),
                node.get("text").asString(),
                node.path("role_context").isString() ? node.get("role_context").asString() : null,
                node.get("think_s").asInt(),
                node.get("speak_s").asInt(),
                node.path("seed").asBoolean(false),
                node.path("retired").asBoolean(false));
    }

    private static void checkAcrossFiles(
            Topic topic, String where, Map<String, String> seenText, List<String> problems) {
        String earlier = seenText.putIfAbsent(sameTextKey(topic.text()), where);
        if (earlier != null) {
            problems.add("E-TEXT-DUPLICATE " + where + " same text as " + earlier);
        }
        if (!topic.id().startsWith(topic.style().wire() + "-" + LEVEL_CODE.get(topic.level()) + "-")) {
            problems.add("E-ID-CELL " + where + " id " + topic.id() + " does not match style "
                    + topic.style().wire() + " and level " + topic.level().wire());
        }
    }

    /** Two texts are the same question when only case, spacing or the closing mark differ. */
    private static String sameTextKey(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip().replaceAll("[.?!]+$", "");
    }

    private static void checkSeeds(List<Topic> topics, List<String> problems) {
        for (Style style : Style.values()) {
            boolean hasSeed = topics.stream()
                    .anyMatch(t -> t.style() == style && t.level() == LOWEST_LEVEL && t.seed() && !t.retired());
            if (!hasSeed) {
                problems.add("E-NO-SEED " + style.wire() + " no live seed topic for " + style.wire() + " at level "
                        + LOWEST_LEVEL.wire());
            }
        }
    }

    private static void checkCells(
            List<Topic> topics, TopicSettings settings, boolean enforceMin, List<String> problems) {
        for (Style style : Style.values()) {
            for (Level level : Level.values()) {
                long live = topics.stream()
                        .filter(t -> t.style() == style && t.level() == level && !t.retired())
                        .count();
                if (live >= settings.minPerCell()) {
                    continue;
                }
                String cell = style.wire() + "/" + level.wire();
                if (enforceMin) {
                    problems.add("E-CELL-MIN " + cell + " has " + live + " live topics; the server needs "
                            + settings.minPerCell());
                } else {
                    log.warn(
                            "Topic cell {} has {} live topics; the server needs {}", cell, live, settings.minPerCell());
                }
            }
        }
    }
}
