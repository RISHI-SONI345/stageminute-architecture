package app.stageminute.trust.measures;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.trust.model.SavedRun;
import java.util.List;
import org.junit.jupiter.api.Test;

class TakeDiffTest {

    private static SavedRun.Meta meta(String prompt, String schema, String model) {
        return new SavedRun.Meta("2026-11-02", "real", "2026-10-17", prompt, schema, model, List.of(model));
    }

    private static SavedRun.Row row(String key, int total, String fix, String... steps) {
        return new SavedRun.Row(key, true, total, List.of(steps), fix);
    }

    private static final SavedRun.Meta SAME = meta("p1", "s1", "m1");

    @Test
    void anAverageCanHoldWhileOneTakeBreaks() {
        // Two takes swap 5 points in opposite directions: the mean total is the same, the diff still shows both.
        SavedRun before = new SavedRun(
                SAME,
                List.of(
                        row("a/t1", 80, "add_closing", "strong", "strong", "weak", "missing"),
                        row("b/t1", 70, "add_closing", "strong", "weak", "weak", "missing")));
        SavedRun after = new SavedRun(
                SAME,
                List.of(
                        row("a/t1", 75, "add_closing", "strong", "weak", "weak", "missing"),
                        row("b/t1", 75, "add_closing", "strong", "weak", "weak", "missing")));

        TakeDiff.Result diff = TakeDiff.compare(before, after);

        assertThat(diff.changes()).hasSize(2);
        TakeDiff.Change first = diff.changes().get(0);
        assertThat(first.key()).isEqualTo("a/t1");
        assertThat(first.totalDelta()).isEqualTo(-5);
        assertThat(first.changedSteps()).containsExactly(1);
        assertThat(first.fixChanged()).isFalse();
        TakeDiff.Change second = diff.changes().get(1);
        assertThat(second.totalDelta()).isEqualTo(5);
        assertThat(second.changedSteps()).isEmpty();
        assertThat(diff.unchanged()).isZero();
    }

    @Test
    void anUnchangedTakeIsCountedNotListed() {
        SavedRun run =
                new SavedRun(SAME, List.of(row("a/t1", 80, "add_closing", "strong", "strong", "weak", "missing")));

        TakeDiff.Result diff = TakeDiff.compare(run, run);

        assertThat(diff.changes()).isEmpty();
        assertThat(diff.unchanged()).isEqualTo(1);
    }

    @Test
    void aChangedFixIsASeparateChange() {
        SavedRun before =
                new SavedRun(SAME, List.of(row("a/t1", 80, "add_closing", "strong", "strong", "weak", "missing")));
        SavedRun after =
                new SavedRun(SAME, List.of(row("a/t1", 80, "add_example", "strong", "strong", "weak", "missing")));

        TakeDiff.Result diff = TakeDiff.compare(before, after);

        assertThat(diff.changes()).hasSize(1);
        assertThat(diff.changes().get(0).fixChanged()).isTrue();
        assertThat(diff.changes().get(0).totalDelta()).isZero();
    }

    @Test
    void takesInOnlyOneRunAreCounted() {
        SavedRun before = new SavedRun(SAME, List.of(row("a/t1", 80, "", "strong", "strong", "weak", "missing")));
        SavedRun after = new SavedRun(SAME, List.of(row("b/t1", 80, "", "strong", "strong", "weak", "missing")));

        TakeDiff.Result diff = TakeDiff.compare(before, after);

        assertThat(diff.onlyInOld()).isEqualTo(1);
        assertThat(diff.onlyInNew()).isEqualTo(1);
        assertThat(diff.changes()).isEmpty();
    }

    @Test
    void aTakeThatLostItsScoreIsAChange() {
        SavedRun before = new SavedRun(SAME, List.of(row("a/t1", 80, "", "strong", "strong", "weak", "missing")));
        SavedRun after = new SavedRun(SAME, List.of(new SavedRun.Row("a/t1", false, 0, List.of(), "")));

        assertThat(TakeDiff.compare(before, after).changes()).hasSize(1);
        assertThat(TakeDiff.compare(before, after).changes().get(0).scoredChanged())
                .isTrue();
    }

    @Test
    void differentPromptSchemaOrPinnedModelStopsTheComparison() {
        assertThat(TakeDiff.versionProblem(meta("p1", "s1", "m1"), meta("p1", "s1", "m1")))
                .isEmpty();
        assertThat(TakeDiff.versionProblem(meta("p1", "s1", "m1"), meta("p2", "s1", "m1")))
                .hasValueSatisfying(text -> assertThat(text)
                        .contains("prompt_version")
                        .contains("p1")
                        .contains("p2"));
        assertThat(TakeDiff.versionProblem(meta("p1", "s1", "m1"), meta("p1", "s2", "m1")))
                .hasValueSatisfying(text -> assertThat(text).contains("schema_version"));
        assertThat(TakeDiff.versionProblem(meta("p1", "s1", "m1"), meta("p1", "s1", "m2")))
                .hasValueSatisfying(text -> assertThat(text).contains("pinned model"));
    }

    @Test
    void everyDifferenceIsNamedNotJustTheFirst() {
        assertThat(TakeDiff.versionProblem(meta("p1", "s1", "m1"), meta("p2", "s2", "m2")))
                .hasValueSatisfying(text -> assertThat(text)
                        .contains("prompt_version")
                        .contains("schema_version")
                        .contains("pinned model"));
    }
}
