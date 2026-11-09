package app.stageminute.reports;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The Fix Library file as Gradle copied it from {@code content/fixes}, for tests that build a report. */
public final class FixTextsFixtures {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private FixTextsFixtures() {}

    /** The text of {@code content/fixes/en.json}. */
    public static String file() {
        try (var in = Objects.requireNonNull(FixTextsFixtures.class.getResourceAsStream("/content-fixes/en.json"))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The real texts. */
    public static FixTexts content() {
        return FixTexts.parse(file(), JSON);
    }

    /** The real texts with one fix id taken out. */
    public static FixTexts without(String fixCode) {
        ObjectNode root = (ObjectNode) JSON.readTree(file());
        ((ObjectNode) root.path("fixes")).remove(fixCode);
        return FixTexts.parse(JSON.writeValueAsString(root), JSON);
    }
}
