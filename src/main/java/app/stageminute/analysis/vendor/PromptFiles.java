package app.stageminute.analysis.vendor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The judge's prompt and output schema, read from the files in {@code analysis/} on the class path, with a version made
 * from each file's own text (AD-125, AD-151): {@code v2-} and the first eight hex digits of the SHA-256 of the text.
 * Change a letter of the prompt and the stamp on every new report changes, so a dispute or a score change traces to the
 * exact prompt. Line endings are normalised first, so a Windows checkout and a Linux one give the same stamp.
 */
final class PromptFiles {

    static final String PROMPT_RESOURCE = "analysis/prompt-v2.md";
    static final String SCHEMA_RESOURCE = "analysis/schema-v2.json";

    private static final String FAMILY = "v2-";
    private static final int VERSION_BYTES = 4;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * The loaded files.
     *
     * @param prompt the prompt text sent as the system instruction
     * @param promptVersion the stamp of the prompt text
     * @param schema the output schema, parsed
     * @param schemaVersion the stamp of the schema text
     */
    record Loaded(String prompt, String promptVersion, JsonNode schema, String schemaVersion) {}

    private PromptFiles() {}

    /** Builds the loaded files from text (for tests and for {@link #fromClasspath}). */
    static Loaded of(String promptText, String schemaText) {
        JsonNode schema;
        try {
            schema = JSON.readTree(schemaText);
        } catch (JacksonException e) {
            throw new IllegalStateException("The judge schema file is not valid JSON");
        }
        return new Loaded(promptText, versionOf(promptText), schema, versionOf(schemaText));
    }

    /** The shipped files. A missing or unreadable file stops the server at start. */
    static Loaded fromClasspath() {
        return of(read(PROMPT_RESOURCE), read(SCHEMA_RESOURCE));
    }

    private static String read(String resource) {
        try (InputStream in = PromptFiles.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("The judge file " + resource + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("The judge file " + resource + " cannot be read");
        }
    }

    private static String versionOf(String text) {
        String normal = text.replace("\r\n", "\n");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normal.getBytes(StandardCharsets.UTF_8));
            return FAMILY + HexFormat.of().formatHex(digest, 0, VERSION_BYTES);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
