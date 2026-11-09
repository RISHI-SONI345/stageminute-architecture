package app.stageminute.reports;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.reports.internal.ReportJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Report v2 is written down three times: the example in docs/architecture/06-API.md section 9, the serializer
 * ({@link ReportJson} over {@link ReportV2}) and the {@code Report} schema in {@code server/openapi.json}, which the
 * app's typed client is generated from (03 F-026 "Done when": the drift test). This test fails when any two of them
 * name a field differently, at any depth. Map keys that are ids (sentence ids, item ids) count as one key, {@code *}.
 * {@code OpenApiDriftTest} keeps {@code openapi.json} equal to what the running server describes.
 */
class ReportContractTest {

    private static final Path API_DOC = Path.of("..", "docs", "architecture", "06-API.md");
    private static final Path OPENAPI = Path.of("openapi.json");
    private static final Pattern JSON_BLOCK = Pattern.compile("```json\\n(.*?)\\n```", Pattern.DOTALL);

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ReportJson serializer = new ReportJson(mapper);

    @Test
    void theScoredExampleInTheDocumentHasTheSerializersShape() throws IOException {
        assertThat(shape(documentExamples().get(0)))
                .as("06-API section 9 (scored example) against the serializer")
                .isEqualTo(shape(serialized(ReportFixtures.scoredWithEveryField())));
    }

    @Test
    void theNoScoreExampleInTheDocumentHasTheSerializersShape() throws IOException {
        assertThat(shape(documentExamples().get(1)))
                .as("06-API section 9 (no-score example) against the serializer")
                .isEqualTo(shape(serialized(ReportFixtures.noScore())));
    }

    @Test
    void theOpenApiFileHasTheSerializersShape() throws IOException {
        Set<String> serialized = new TreeSet<>(shape(serialized(ReportFixtures.scoredWithEveryField())));
        serialized.addAll(shape(serialized(ReportFixtures.noScore())));

        JsonNode schemas = mapper.readTree(Files.readString(OPENAPI, StandardCharsets.UTF_8))
                .path("components")
                .path("schemas");
        Set<String> described = new TreeSet<>();
        schemaShape(schemas, schemas.path("Report"), "", described);
        described.removeIf(path -> path.startsWith("trend.")); // trend is Next: always null, so never filled

        assertThat(described).as("server/openapi.json against the serializer").isEqualTo(serialized);
    }

    @Test
    void theStoredTextReadsBackToTheSameReport() {
        ReportV2 report = ReportFixtures.scoredWithEveryField();
        assertThat(serializer.read(serializer.write(report))).isEqualTo(report);
        assertThat(serializer.read(serializer.write(ReportFixtures.noScore()))).isEqualTo(ReportFixtures.noScore());
    }

    private JsonNode serialized(ReportV2 report) {
        return mapper.readTree(serializer.write(report));
    }

    /** The JSON blocks of section 9, in order: the scored example, then the no-score example. */
    private List<JsonNode> documentExamples() throws IOException {
        String doc = Files.readString(API_DOC, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String section = doc.substring(doc.indexOf("\n## 9."));
        List<JsonNode> blocks = new ArrayList<>();
        Matcher matcher = JSON_BLOCK.matcher(section);
        while (matcher.find()) {
            blocks.add(mapper.readTree(matcher.group(1)));
        }
        assertThat(blocks)
                .as("06-API section 9 has a scored and a no-score example")
                .hasSize(2);
        return blocks;
    }

    /** Every field path in a JSON value; array elements share one path ({@code []}), id keys are {@code *}. */
    private static Set<String> shape(JsonNode node) {
        Set<String> paths = new TreeSet<>();
        walk(node, "", paths);
        return paths;
    }

    private static void walk(JsonNode node, String path, Set<String> paths) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                String key = field.getKey().matches("[0-9]+") ? "*" : field.getKey();
                String child = path.isEmpty() ? key : path + "." + key;
                paths.add(child);
                walk(field.getValue(), child, paths);
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                walk(element, path + "[]", paths);
            }
        }
    }

    /** The same paths, read from an OpenAPI schema: properties, array items and maps ({@code *}). */
    private static void schemaShape(JsonNode schemas, JsonNode schema, String path, Set<String> paths) {
        JsonNode resolved = resolve(schemas, schema);
        if (resolved.has("properties")) {
            for (Map.Entry<String, JsonNode> property :
                    resolved.path("properties").properties()) {
                String child = path.isEmpty() ? property.getKey() : path + "." + property.getKey();
                paths.add(child);
                schemaShape(schemas, property.getValue(), child, paths);
            }
        }
        if (resolved.has("items")) {
            schemaShape(schemas, resolved.path("items"), path + "[]", paths);
        }
        if (resolved.path("additionalProperties").isObject()) {
            String child = path + ".*";
            paths.add(child);
            schemaShape(schemas, resolved.path("additionalProperties"), child, paths);
        }
    }

    private static JsonNode resolve(JsonNode schemas, JsonNode schema) {
        String ref = schema.path("$ref").asString("");
        return ref.isEmpty() ? schema : schemas.path(ref.substring(ref.lastIndexOf('/') + 1));
    }
}
