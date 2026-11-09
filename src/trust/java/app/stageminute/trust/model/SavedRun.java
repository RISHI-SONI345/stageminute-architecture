package app.stageminute.trust.model;

import java.util.List;

/**
 * What a run leaves in {@code tests/trust/reports/} as JSON (never in git): the versions that made it and one row per
 * take, so a later run can be compared take by take. Rows carry an anonymous key and labels and numbers, never words.
 *
 * @param meta when and with what the run was made
 * @param rows one row per take (a clip is its first run)
 */
public record SavedRun(Meta meta, List<Row> rows) {

    public SavedRun {
        rows = List.copyOf(rows);
    }

    /**
     * The versions and the date.
     *
     * @param date the run date (ISO)
     * @param mode {@code synthetic} (fake vendors) or {@code real}
     * @param formulaVersion the score formula
     * @param promptVersion the prompt file's version
     * @param schemaVersion the output schema's version
     * @param pinnedModel the pinned model name
     * @param answeredModels the model ids the vendor said it answered with
     */
    public record Meta(
            String date,
            String mode,
            String formulaVersion,
            String promptVersion,
            String schemaVersion,
            String pinnedModel,
            List<String> answeredModels) {

        public Meta {
            answeredModels = List.copyOf(answeredModels);
        }
    }

    /**
     * One take.
     *
     * @param key {@code speaker/take}, anonymous
     * @param scored whether the take got a score
     * @param total the score total (0 when not scored)
     * @param steps the four step labels as words
     * @param fix the fix put first, or an empty string
     */
    public record Row(String key, boolean scored, int total, List<String> steps, String fix) {

        public Row {
            steps = List.copyOf(steps);
        }
    }
}
