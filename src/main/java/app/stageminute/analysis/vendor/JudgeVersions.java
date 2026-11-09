package app.stageminute.analysis.vendor;

import java.util.Objects;

/**
 * What produced a report's labels, so a dispute or a score change can be traced to the exact prompt (AD-125).
 *
 * @param model the pinned model name
 * @param prompt the prompt file's version
 * @param schema the output schema's version
 */
public record JudgeVersions(String model, String prompt, String schema) {

    public JudgeVersions {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(schema, "schema");
    }
}
