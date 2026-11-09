package app.stageminute.trust.measures;

import app.stageminute.trust.model.SavedRun;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The per-take diff against the last saved run: which takes changed a label or a score, and by how much. A prompt or
 * model change can keep the average and still break a take; the averages hide that, this does not. Also the model-change
 * rule (tests/CLAUDE.md): two runs made with a different prompt, schema or pinned model are not compared unless the
 * caller says it is a deliberate comparison.
 */
public final class TakeDiff {

    private TakeDiff() {}

    /**
     * One take that differs.
     *
     * @param key {@code speaker/take}
     * @param totalDelta new total minus old total
     * @param changedSteps the step positions (0 to 3) whose label changed
     * @param fixChanged whether the fix put first changed
     * @param scoredChanged whether the take gained or lost its score
     */
    public record Change(
            String key, int totalDelta, List<Integer> changedSteps, boolean fixChanged, boolean scoredChanged) {}

    /**
     * The diff.
     *
     * @param changes takes that differ, in the order of the new run
     * @param unchanged takes that are the same
     * @param onlyInOld takes the new run does not have
     * @param onlyInNew takes the old run does not have
     */
    public record Result(List<Change> changes, int unchanged, int onlyInOld, int onlyInNew) {}

    public static Result compare(SavedRun old, SavedRun latest) {
        Map<String, SavedRun.Row> before = new HashMap<>();
        old.rows().forEach(row -> before.put(row.key(), row));
        List<Change> changes = new ArrayList<>();
        int unchanged = 0;
        int onlyInNew = 0;
        for (SavedRun.Row row : latest.rows()) {
            SavedRun.Row previous = before.remove(row.key());
            if (previous == null) {
                onlyInNew++;
                continue;
            }
            Change change = change(previous, row);
            if (change == null) {
                unchanged++;
            } else {
                changes.add(change);
            }
        }
        return new Result(changes, unchanged, before.size(), onlyInNew);
    }

    private static Change change(SavedRun.Row previous, SavedRun.Row row) {
        List<Integer> steps = new ArrayList<>();
        for (int i = 0; i < Math.min(previous.steps().size(), row.steps().size()); i++) {
            if (!previous.steps().get(i).equals(row.steps().get(i))) {
                steps.add(i);
            }
        }
        int delta = row.total() - previous.total();
        boolean fix = !previous.fix().equals(row.fix());
        boolean scored = previous.scored() != row.scored();
        if (steps.isEmpty() && delta == 0 && !fix && !scored) {
            return null;
        }
        return new Change(row.key(), delta, steps, fix, scored);
    }

    /** The reason two runs must not be compared, naming every difference, or empty when they may be. */
    public static Optional<String> versionProblem(SavedRun.Meta old, SavedRun.Meta latest) {
        List<String> problems = new ArrayList<>();
        if (!old.promptVersion().equals(latest.promptVersion())) {
            problems.add("prompt_version " + old.promptVersion() + " -> " + latest.promptVersion());
        }
        if (!old.schemaVersion().equals(latest.schemaVersion())) {
            problems.add("schema_version " + old.schemaVersion() + " -> " + latest.schemaVersion());
        }
        if (!old.pinnedModel().equals(latest.pinnedModel())) {
            problems.add("pinned model " + old.pinnedModel() + " -> " + latest.pinnedModel());
        }
        return problems.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", problems));
    }
}
