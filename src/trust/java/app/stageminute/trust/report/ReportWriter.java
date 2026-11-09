package app.stageminute.trust.report;

import app.stageminute.trust.gates.Gate;
import app.stageminute.trust.gates.GateTable;
import app.stageminute.trust.gates.Status;
import app.stageminute.trust.labelset.LabelSet;
import app.stageminute.trust.measures.LabelFlip;
import app.stageminute.trust.measures.PaceBands;
import app.stageminute.trust.measures.TakeDiff;
import app.stageminute.trust.measures.WaitTimes;
import app.stageminute.trust.model.Observation;
import app.stageminute.trust.model.SavedRun;
import app.stageminute.trust.model.TakeCase;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Writes the Markdown report of one run. The first line is the verdict. Per-person rows are keyed by an anonymous id in
 * backticks as the first table cell, so the deletion command can take them out. It never prints a name, a transcript, an
 * annotator note or a key: it is given labels, times and numbers only.
 */
public final class ReportWriter {

    private static final double PERCENT = 100.0;

    private ReportWriter() {}

    /**
     * Everything the report shows.
     *
     * @param verdict the first line
     * @param meta the date, mode and versions
     * @param table the gates and the extras
     * @param labelled the labelled takes with their runs
     * @param replay the replay clips with their runs
     * @param excluded speakers left out
     * @param costLine the real cost of the run, or a plain note when there was none
     * @param diff the per-take diff against the last run, or empty when there was none
     * @param previous the previous run's versions, when there was one
     * @param diffNote why the diff is missing or what it compared
     */
    public record Data(
            String verdict,
            SavedRun.Meta meta,
            GateTable.Table table,
            java.util.List<TakeCase> labelled,
            java.util.List<TakeCase> replay,
            java.util.List<LabelSet.Excluded> excluded,
            String costLine,
            java.util.Optional<TakeDiff.Result> diff,
            java.util.Optional<SavedRun.Meta> previous,
            String diffNote) {}

    public static String markdown(Data data) {
        StringBuilder out = new StringBuilder();
        out.append("# Trust harness report\n\n**").append(data.verdict()).append("**\n\n");
        versions(out, data);
        gates(out, data.table().gates());
        extras(out, data.table().extras());
        speakers(out, data);
        diff(out, data);
        promiseTests(out);
        out.append("## Cost of this run\n\n").append(data.costLine()).append("\n");
        return out.toString();
    }

    private static void versions(StringBuilder out, Data data) {
        SavedRun.Meta meta = data.meta();
        out.append("## Run\n\n");
        out.append("- Date: ").append(meta.date()).append("\n");
        out.append("- Mode: ")
                .append(meta.mode())
                .append(
                        meta.mode().equals("synthetic")
                                ? " (fake vendors, invented clips: proves the harness, not the product)\n"
                                : " (real vendors)\n");
        out.append("- formula_version: ").append(meta.formulaVersion()).append("\n");
        out.append("- prompt_version: ").append(meta.promptVersion()).append("\n");
        out.append("- schema_version: ").append(meta.schemaVersion()).append("\n");
        out.append("- Pinned model: ").append(meta.pinnedModel()).append("\n");
        out.append("- Answered model: ")
                .append(meta.answeredModels().isEmpty() ? "none reported" : String.join(", ", meta.answeredModels()))
                .append("\n");
        data.previous()
                .ifPresent(old -> out.append("- Previous run: prompt_version ")
                        .append(old.promptVersion())
                        .append(", schema_version ")
                        .append(old.schemaVersion())
                        .append(", pinned model ")
                        .append(old.pinnedModel())
                        .append(", date ")
                        .append(old.date())
                        .append("\n"));
        out.append("- Ranges: 95 % bootstrap over speakers, fixed seed ")
                .append(GateTable.SEED)
                .append(" (the set is small; read the range, not just the number)\n\n");
    }

    private static void gates(StringBuilder out, java.util.List<Gate> gates) {
        out.append("## Gates (").append("requirements/11-QUALITY-GATES-AND-TESTS.md section 1)\n\n");
        out.append("| Gate | Measured | Range (95 %) | Pass line | Result |\n|---|---|---|---|---|\n");
        for (Gate gate : gates) {
            out.append("| ")
                    .append(gate.name())
                    .append(" | ")
                    .append(gate.measured())
                    .append(" | ")
                    .append(gate.range())
                    .append(" | ")
                    .append(gate.passLine())
                    .append(" | ")
                    .append(word(gate.status()))
                    .append(" |\n");
        }
        out.append("\n");
    }

    private static String word(Status status) {
        return switch (status) {
            case PASS -> "PASS";
            case FAIL -> "FAIL";
            case NOT_MEASURED -> "NOT MEASURED";
            case NOT_SET -> "NOT SET";
        };
    }

    private static void extras(StringBuilder out, GateTable.Extras extras) {
        out.append("## Extra measurements\n\n");
        out.append(String.format(
                Locale.ROOT,
                "- Run: %d speakers, %d labelled takes, %d replay clips, %d runs of each clip. Filler rule: a filler"
                        + " counts when %s annotator(s) marked it.\n",
                extras.speakers(),
                extras.labelledTakes(),
                extras.clips(),
                extras.runsPerClip(),
                extras.fillerRule().name().toLowerCase(Locale.ROOT)));
        LabelFlip.Result flip = extras.labelFlip();
        out.append(String.format(
                Locale.ROOT,
                "- Label-flip noise (run-to-run, F-038): %s of single label flips move the total by the noise band"
                        + " or more (%d of %d flips). Filler labels are not flipped. FLAG: the requirements give no pass"
                        + " line for this number although issue #19 says it must pass before launch; it has no PASS or"
                        + " FAIL until the founder sets one (C-86).\n",
                flip.flips() == 0 ? "not measured" : percent(flip.share()),
                flip.crossings(),
                flip.flips()));
        for (Map.Entry<LabelFlip.Kind, LabelFlip.Count> entry : flip.byKind().entrySet()) {
            out.append(String.format(
                    Locale.ROOT,
                    "  - %s: %d of %d\n",
                    entry.getKey().name().toLowerCase(Locale.ROOT),
                    entry.getValue().crossings(),
                    entry.getValue().flips()));
        }
        out.append(String.format(
                Locale.ROOT,
                "- Wait from upload to report: p50 %s, p95 %s over %d runs (decides whether a background job is"
                        + " needed, C-68).\n",
                seconds(extras.waits().p50Seconds()),
                seconds(extras.waits().p95Seconds()),
                extras.waits().runs()));
        out.append(waitSplitLine(extras.waits()));
        out.append("- Server length against the phone's duration_ms (C-69): ")
                .append(
                        extras.takeLength().compared() == 0
                                ? "no phone value"
                                : String.format(
                                        Locale.ROOT,
                                        "mean difference %.0f ms, largest %d ms over %d takes",
                                        extras.takeLength().meanAbsDiffMs(),
                                        extras.takeLength().maxAbsDiffMs(),
                                        extras.takeLength().compared()))
                .append(
                        extras.takeLength().noPhoneValue() > 0
                                ? " (" + extras.takeLength().noPhoneValue() + " takes with no phone value)"
                                : "")
                .append("\n");
        out.append("- Annotators against each other (step agreement): ")
                .append(percent(extras.annotatorAgreement()))
                .append("\n");
        out.append("- Pipeline's first fix is the one the annotators named: ")
                .append(percent(extras.firstFixAgreement()))
                .append("\n");
        out.append("- Length bias (longer takes, more strong steps; correlation): ")
                .append(
                        Double.isNaN(extras.lengthBias())
                                ? "not measured"
                                : String.format(Locale.ROOT, "%.2f", extras.lengthBias()))
                .append("\n");
        extras.fairnessGroups().forEach((measure, groups) -> {
            out.append("- Fairness ").append(measure).append(" by accent group: ");
            if (groups.isEmpty()) {
                out.append("not measured");
            }
            groups.forEach((group, value) ->
                    out.append(group).append(' ').append(percent(value)).append("; "));
            out.append("\n");
        });
        PaceBands.Totals totals = extras.paceTotals();
        out.append(String.format(
                Locale.ROOT,
                "- Pace bands (nothing changed; a change is a new formula_version and a row in 01): of %d takes, full"
                        + " pace points under the launch band 85 to 240: %d; under the personal band (median of the"
                        + " other takes +- 25): %d; under mean +- 1 SD of the other takes: %d.\n\n",
                totals.takes(),
                totals.fullLaunch(),
                totals.fullPersonal(),
                totals.fullMeanSd()));
        if (!extras.paceSpeakers().isEmpty()) {
            out.append("| Speaker | Takes | Pace SD (wpm) | Launch band | Personal band | Mean +- 1 SD |\n");
            out.append("|---|---|---|---|---|---|\n");
            for (PaceBands.Speaker speaker : extras.paceSpeakers()) {
                out.append(String.format(
                        Locale.ROOT,
                        "| `%s` | %d | %.1f | %d | %d | %d |\n",
                        speaker.speakerId(),
                        speaker.takes(),
                        speaker.paceSd(),
                        speaker.fullLaunch(),
                        speaker.fullPersonal(),
                        speaker.fullMeanSd()));
            }
            out.append("\n");
        }
    }

    private static void speakers(StringBuilder out, Data data) {
        out.append("## Speakers\n\n");
        java.util.Map<String, Long> unscored = java.util.stream.Stream.concat(
                        data.labelled().stream(), data.replay().stream())
                .flatMap(take -> take.runs().stream())
                .filter(run -> !run.scored())
                .collect(java.util.stream.Collectors.groupingBy(
                        run -> run.status(), java.util.TreeMap::new, java.util.stream.Collectors.counting()));
        long failed = unscored.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("vendor_unavailable"))
                .mapToLong(java.util.Map.Entry::getValue)
                .sum();
        if (failed > 0) {
            out.append("**")
                    .append(failed)
                    .append(" run(s) failed with a vendor error and have no score; they count as not measured, not as"
                            + " a pass.**\n\n");
        }
        if (!unscored.isEmpty()) {
            // The status is a fixed code (never vendor text or speech), so it is safe to print.
            out.append("Runs with no score, by reason: ");
            unscored.forEach((reason, count) ->
                    out.append("`").append(reason).append("` x").append(count).append("  "));
            out.append("\n\n");
            // Which recording each reason belongs to: the file name is the take's own label, never its content.
            out.append("By recording: ");
            java.util.stream.Stream.concat(data.labelled().stream(), data.replay().stream())
                    .forEach(take -> {
                        java.util.Map<String, Long> reasons = take.runs().stream()
                                .filter(run -> !run.scored())
                                .collect(java.util.stream.Collectors.groupingBy(
                                        run -> run.status(),
                                        java.util.TreeMap::new,
                                        java.util.stream.Collectors.counting()));
                        if (!reasons.isEmpty()) {
                            out.append("`")
                                    .append(take.speakerId())
                                    .append("/")
                                    .append(take.takeName())
                                    .append("` ");
                            reasons.forEach((reason, count) -> out.append(reason)
                                    .append(" x")
                                    .append(count)
                                    .append("; "));
                        }
                    });
            out.append("\n\n");
        }
        if (!data.excluded().isEmpty()) {
            out.append("Left out (F-123: no valid consent): ");
            data.excluded()
                    .forEach(speaker -> out.append("`")
                            .append(speaker.speakerId())
                            .append("` (")
                            .append(speaker.reason())
                            .append(") "));
            out.append("\n\n");
        }
        out.append("| Speaker | Accent group | Takes | Scored | Mean total |\n|---|---|---|---|---|\n");
        java.util.Map<String, java.util.List<TakeCase>> bySpeaker = new java.util.LinkedHashMap<>();
        for (TakeCase take : data.labelled()) {
            bySpeaker
                    .computeIfAbsent(take.speakerId(), key -> new java.util.ArrayList<>())
                    .add(take);
        }
        bySpeaker.forEach((id, takes) -> {
            OptionalDouble mean = takes.stream()
                    .map(TakeCase::firstScored)
                    .flatMap(java.util.Optional::stream)
                    .mapToInt(Observation::total)
                    .average();
            out.append(String.format(
                    Locale.ROOT,
                    "| `%s` | %s | %d | %d | %s |\n",
                    id,
                    takes.get(0).accentGroup(),
                    takes.size(),
                    takes.stream()
                            .filter(take -> take.firstScored().isPresent())
                            .count(),
                    mean.isPresent() ? String.format(Locale.ROOT, "%.1f", mean.getAsDouble()) : "none"));
        });
        out.append("\n");
    }

    private static void diff(StringBuilder out, Data data) {
        out.append("## Per-take diff against the last saved run\n\n");
        if (data.diff().isEmpty()) {
            out.append(data.diffNote()).append("\n\n");
            return;
        }
        TakeDiff.Result diff = data.diff().get();
        out.append(data.diffNote()).append("\n\n");
        out.append(String.format(
                Locale.ROOT,
                "%d takes changed, %d unchanged, %d only in the old run, %d only in this run.\n\n",
                diff.changes().size(),
                diff.unchanged(),
                diff.onlyInOld(),
                diff.onlyInNew()));
        if (!diff.changes().isEmpty()) {
            out.append("| Speaker | Take | Total change | Steps changed | Fix changed | Score gained or lost |\n");
            out.append("|---|---|---|---|---|---|\n");
            for (TakeDiff.Change change : diff.changes()) {
                int slash = change.key().indexOf('/');
                out.append(String.format(
                        Locale.ROOT,
                        "| `%s` | %s | %+d | %s | %s | %s |\n",
                        change.key().substring(0, slash),
                        change.key().substring(slash + 1),
                        change.totalDelta(),
                        change.changedSteps().isEmpty()
                                ? "none"
                                : change.changedSteps().toString(),
                        change.fixChanged() ? "yes" : "no",
                        change.scoredChanged() ? "yes" : "no"));
            }
            out.append("\n");
        }
    }

    private static void promiseTests(StringBuilder out) {
        out.append("## Promise tests (11 section 1)\n\n");
        out.append("1. \"Too quiet or too short? We say so instead of guessing a score\": a server test, not run here:"
                + " `analysis/score/NoScoreGateTest` (`./gradlew test`).\n");
        out.append("2. \"We test that the same recording lands within a few points\": run here: the run-to-run and"
                + " repeat-run score spread gates above, on the replay clips with the pinned model.\n");
        out.append(
                "3. \"Your plan never changes your score\": a server test, not run here: `ArchitectureTest` (the score"
                        + " path never sees the tier).\n");
        out.append("4. \"Delivery is measured from timing, not your accent or voice\": run here: the three fairness"
                + " gates above. This sentence ships only if they pass; otherwise it is removed.\n\n");
    }

    private static String percent(double share) {
        return Double.isNaN(share) ? "not measured" : String.format(Locale.ROOT, "%.1f %%", share * PERCENT);
    }

    /**
     * Splits the wait in two: the Gemini labels call alone, and the rest of the wait on the same runs (Deepgram, scoring,
     * our own code). It is a reading, not a gate: the gate stays end to end, as 11 section 1 says.
     */
    static String waitSplitLine(WaitTimes.Result waits) {
        if (waits.judgeRuns() == 0) {
            return "- Of that wait, the Gemini call alone: not timed (no run reached Gemini).\n";
        }
        return String.format(
                Locale.ROOT,
                "- Of that wait, the Gemini labels call alone: p50 %s, p95 %s over %d runs; the rest of the wait on"
                        + " those same runs (Deepgram, scoring, our code): p50 %s, p95 %s. Runs that failed in"
                        + " Deepgram never reached Gemini and are not in these two.\n",
                seconds(waits.judgeP50Seconds()),
                seconds(waits.judgeP95Seconds()),
                waits.judgeRuns(),
                seconds(waits.otherP50Seconds()),
                seconds(waits.otherP95Seconds()));
    }

    private static String seconds(double value) {
        return Double.isNaN(value) ? "not measured" : String.format(Locale.ROOT, "%.1f s", value);
    }
}
