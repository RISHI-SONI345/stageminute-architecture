package app.stageminute.trust.run;

import app.stageminute.analysis.internal.AnalysisSettings;
import app.stageminute.analysis.internal.Pipeline;
import app.stageminute.analysis.internal.TrustAudio;
import app.stageminute.analysis.spend.SpendPrices;
import app.stageminute.analysis.vendor.TrustVendors;
import app.stageminute.platform.SettingKey;
import app.stageminute.platform.Settings;
import app.stageminute.platform.TrustSettings;
import app.stageminute.reports.FixTexts;
import app.stageminute.trust.gates.Gate;
import app.stageminute.trust.gates.GateTable;
import app.stageminute.trust.gates.Status;
import app.stageminute.trust.gates.Verdict;
import app.stageminute.trust.labelset.LabelSet;
import app.stageminute.trust.labelset.LabelSetException;
import app.stageminute.trust.labelset.LabelSetLoader;
import app.stageminute.trust.labelset.SyntheticSet;
import app.stageminute.trust.measures.CostEstimate;
import app.stageminute.trust.measures.FillerPrecision;
import app.stageminute.trust.measures.TakeDiff;
import app.stageminute.trust.model.Observation;
import app.stageminute.trust.model.SavedRun;
import app.stageminute.trust.model.TakeCase;
import app.stageminute.trust.report.ReportWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;

/**
 * The command line of the trust harness (usage: {@code tests/trust/README.md}). Without {@code --yes} nothing is ever
 * sent to a vendor: a real run first prints the dollar estimate and stops. Exit codes: 0 done, 1 bad input or a failed
 * run, 2 a real run that still needs the founder's yes.
 */
public final class Cli {

    private static final String SET_VAR = "STAGEMINUTE_TRUST_SET";
    private static final String REPLAY_VAR = "STAGEMINUTE_TRUST_REPLAY";
    private static final String REPORTS_VAR = "STAGEMINUTE_TRUST_REPORTS";
    private static final String DEFAULT_REPORTS = "tests/trust/reports";
    private static final String DEFAULT_BASELINE = "tests/trust/cost-baseline.json";
    private static final int DEFAULT_RUNS = 5;
    /** {@code --thinking-budget} was not given: the server setting applies. */
    private static final int NO_BUDGET_OPTION = Integer.MIN_VALUE;

    private Cli() {}

    private enum Mode {
        SYNTHETIC,
        FAKE,
        REAL,
        ESTIMATE,
        DELETE
    }

    /** What the arguments asked for. */
    private record Options(
            Mode mode,
            boolean yes,
            int runs,
            FillerPrecision.Rule rule,
            boolean deliberate,
            boolean saveBaseline,
            String speakerId,
            Path reports,
            Path baseline,
            int thinkingBudget) {}

    public static int run(String[] args, Map<String, String> env, PrintStream out) {
        Options options;
        try {
            options = parse(args, env);
        } catch (IllegalArgumentException e) {
            out.println(e.getMessage());
            out.println(usage());
            return 1;
        }
        try {
            return options.mode() == Mode.DELETE ? delete(options, env, out) : execute(options, env, out);
        } catch (LabelSetException | IllegalStateException e) {
            out.println("Stopped: " + e.getMessage());
            return 1;
        } catch (IOException | UncheckedIOException e) {
            out.println("Stopped: a file could not be read or written ("
                    + e.getClass().getSimpleName() + ").");
            return 1;
        } catch (RuntimeException e) {
            // Never print the message of an unexpected failure: it could carry a path or a word of a take.
            out.println("Stopped: unexpected failure (" + e.getClass().getSimpleName() + "); nothing was kept.");
            return 1;
        }
    }

    private static Options parse(String[] args, Map<String, String> env) {
        Mode mode = null;
        boolean yes = false;
        int runs = DEFAULT_RUNS;
        FillerPrecision.Rule rule = FillerPrecision.Rule.BOTH;
        boolean deliberate = false;
        boolean saveBaseline = false;
        String speaker = null;
        Path reports = Path.of(env.getOrDefault(REPORTS_VAR, DEFAULT_REPORTS));
        Path baseline = Path.of(DEFAULT_BASELINE);
        int thinkingBudget = NO_BUDGET_OPTION;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--synthetic" -> mode = Mode.SYNTHETIC;
                case "--fake" -> mode = Mode.FAKE;
                case "--real" -> mode = Mode.REAL;
                case "--estimate" -> mode = Mode.ESTIMATE;
                case "--yes" -> yes = true;
                case "--deliberate-comparison" -> deliberate = true;
                case "--save-cost-baseline" -> saveBaseline = true;
                case "--runs" -> runs = Integer.parseInt(value(args, ++i, "--runs"));
                case "--filler-rule" ->
                    rule = FillerPrecision.Rule.valueOf(
                            value(args, ++i, "--filler-rule").toUpperCase(Locale.ROOT));
                case "--thinking-budget" -> thinkingBudget = Integer.parseInt(value(args, ++i, "--thinking-budget"));
                case "--reports" -> reports = Path.of(value(args, ++i, "--reports"));
                case "--delete-speaker" -> {
                    mode = Mode.DELETE;
                    speaker = value(args, ++i, "--delete-speaker");
                }
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        if (mode == null) {
            throw new IllegalArgumentException(
                    "Choose one of --synthetic, --fake, --estimate, --real or --delete-speaker.");
        }
        if (runs < 1) {
            throw new IllegalArgumentException("--runs must be 1 or more.");
        }
        if (thinkingBudget < 0 && thinkingBudget != NO_BUDGET_OPTION) {
            throw new IllegalArgumentException("--thinking-budget must be 0 (the model's default) or more.");
        }
        return new Options(mode, yes, runs, rule, deliberate, saveBaseline, speaker, reports, baseline, thinkingBudget);
    }

    private static String value(String[] args, int index, String name) {
        if (index >= args.length) {
            throw new IllegalArgumentException(name + " needs a value.");
        }
        return args[index];
    }

    static String usage() {
        return String.join(
                "\n",
                "Usage: ./gradlew trustHarness --args=\"<options>\"   (see tests/trust/README.md)",
                "  --synthetic             run on invented clips and the fake vendors (free, nothing leaves this PC)",
                "  --fake                  run the folders named by " + SET_VAR + " and " + REPLAY_VAR + " on fakes",
                "  --estimate              print the dollar estimate of a real run and stop",
                "  --real --yes            run on the real vendors (costs money; only after the founder's yes)",
                "  --runs N                runs of each replay clip (default 5, as the gate says)",
                "  --filler-rule both|either   which annotators must mark a filler (default both, the strict one)",
                "  --deliberate-comparison compare with a last run made with another prompt, schema or model",
                "  --save-cost-baseline    after a real run, store its mean cost as today's mean (tests/trust/cost-baseline.json)",
                "  --delete-speaker ID     F-123 deletion: remove a speaker's folder, consent, saved rows and report lines",
                "  --thinking-budget N     try a Gemini thinking budget of N tokens for this run (0 = the model's default; without it the server setting applies); keep each"
                        + " budget's reports in its own --reports folder",
                "  --reports DIR           where reports go (default " + DEFAULT_REPORTS + ", git-ignored)");
    }

    private static int delete(Options options, Map<String, String> env, PrintStream out) {
        String set = env.get(SET_VAR);
        if (set == null || set.isBlank()) {
            throw new IllegalStateException(SET_VAR + " is not set");
        }
        SpeakerDeletion.Summary summary = SpeakerDeletion.delete(Path.of(set), options.reports(), options.speakerId());
        out.println(String.format(
                Locale.ROOT,
                "Deleted speaker %s: %d files removed, consent %s, %d saved-run rows and %d report lines taken out.",
                options.speakerId(),
                summary.filesRemoved(),
                summary.consentMarked() ? "marked deleted" : "entry not found",
                summary.runRowsRemoved(),
                summary.reportLinesRemoved()));
        return 0;
    }

    private static int execute(Options options, Map<String, String> env, PrintStream out) throws IOException {
        Path synthetic = null;
        try {
            Path setDir;
            Path replayDir;
            if (options.mode() == Mode.SYNTHETIC) {
                synthetic = Files.createTempDirectory("trust-synthetic");
                SyntheticSet.write(synthetic);
                setDir = synthetic.resolve("set");
                replayDir = synthetic.resolve("replay");
            } else {
                setDir = pathOf(env.get(SET_VAR));
                replayDir = pathOf(env.get(REPLAY_VAR));
                if (setDir == null && replayDir == null) {
                    throw new IllegalStateException("Set " + SET_VAR + " and/or " + REPLAY_VAR
                            + " to the folders of the labelled set and the replay clips.");
                }
            }
            LabelSet set = setDir == null ? null : LabelSetLoader.load(setDir);
            List<LabelSet.ClipInput> clips = replayDir == null ? List.of() : LabelSetLoader.loadReplay(replayDir);
            Settings settings = options.thinkingBudget() >= 0
                    ? TrustSettings.withValues(
                            Map.of(SettingKey.GEMINI_THINKING_BUDGET_TOKENS, options.thinkingBudget()))
                    : TrustSettings.launchDefaults();
            SpendPrices prices = prices(settings);
            CostEstimate estimate = estimate(set, clips, options.runs(), prices);
            if (options.mode() == Mode.ESTIMATE || options.mode() == Mode.REAL) {
                out.println(estimate.describe());
                out.println("Prices: the server's launch-default price settings (ticket #19.2); check them against the"
                        + " vendors' current price pages before relying on the dollars.");
            }
            if (options.mode() == Mode.ESTIMATE) {
                return 0;
            }
            if (options.mode() == Mode.REAL && !options.yes()) {
                out.println(
                        "Nothing was sent. A real run costs money and sends audio to Deepgram and Gemini: run it again"
                                + " with --yes only after the founder agrees to this estimate.");
                return 2;
            }
            return runAndReport(options, env, out, set, clips, settings, prices);
        } finally {
            if (synthetic != null) {
                deleteTree(synthetic);
            }
        }
    }

    private static int runAndReport(
            Options options,
            Map<String, String> env,
            PrintStream out,
            LabelSet set,
            List<LabelSet.ClipInput> clips,
            Settings settings,
            SpendPrices prices)
            throws IOException {
        boolean real = options.mode() == Mode.REAL;
        UsageMeter meter = real ? new UsageMeter(prices) : null;
        TrustVendors.Vendors vendors = real ? TrustVendors.real(env, meter, settings) : TrustVendors.fakes();
        TimedJudge timedJudge = new TimedJudge(vendors.judge(), System::nanoTime);
        Pipeline pipeline = new Pipeline(vendors.speech(), timedJudge, new AnalysisSettings(settings), fixTexts());
        PipelineRunner runner = PipelineRunner.of(pipeline, meter, timedJudge);
        int budget = settings.get(SettingKey.GEMINI_THINKING_BUDGET_TOKENS);
        out.println("Gemini thinking budget: " + (budget > 0 ? budget + " tokens" : "not set (the model's default)"));
        out.println("Running " + (real ? "on the REAL vendors" : "on fake vendors (nothing leaves this PC)") + " ...");
        HarnessRunner.Outcome outcome = HarnessRunner.execute(set, clips, options.runs(), runner);

        String mode = real ? "real" : "synthetic";
        String date = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        OptionalLong baseline = CostBaseline.read(options.baseline());
        GateTable.Table table = GateTable.build(outcome.labelled(), outcome.replay(), options.rule(), real, baseline);
        // Fake vendors can only prove the harness: the verdict says so whatever the numbers are.
        String verdict = Verdict.line(table.gates(), !real);
        SavedRun.Meta meta = runner.versions(date, mode);
        SavedRun saved = RunStore.toSaved(meta, outcome.labelled(), outcome.replay());

        Optional<SavedRun> previous = RunStore.latest(options.reports(), mode);
        Optional<TakeDiff.Result> diff = Optional.empty();
        String note = "No earlier " + mode + " run to compare with.";
        if (previous.isPresent()) {
            Optional<String> problem = TakeDiff.versionProblem(previous.get().meta(), meta);
            if (problem.isPresent() && !options.deliberate()) {
                note = "Not compared: the versions differ (" + problem.get() + "). A change of model, prompt or schema"
                        + " is a new baseline; run again with --deliberate-comparison to compare anyway.";
            } else {
                diff = Optional.of(TakeDiff.compare(previous.get(), saved));
                note = problem.isPresent()
                        ? "DELIBERATE comparison across versions (" + problem.get() + ")."
                        : "Compared with the run of " + previous.get().meta().date()
                                + " (same prompt, schema and model).";
            }
        }
        long cost = totalCost(outcome);
        int runsCount = runCount(outcome);
        String costLine = real
                ? String.format(
                        Locale.ROOT,
                        "Real cost: USD %.4f for %d pipeline runs (USD %.4f a run).",
                        GateTable.dollars(cost),
                        runsCount,
                        runsCount == 0 ? 0.0 : GateTable.dollars(cost) / runsCount)
                : "No vendor cost: fake vendors.";
        ReportWriter.Data data = new ReportWriter.Data(
                verdict,
                meta,
                table,
                outcome.labelled(),
                outcome.replay(),
                outcome.excluded(),
                costLine,
                diff,
                previous.map(SavedRun::meta),
                note);
        Files.createDirectories(options.reports());
        String report = ReportWriter.markdown(data);
        Path reportFile = options.reports().resolve("report-" + date.replace(':', '-') + "-" + mode + ".md");
        Files.writeString(reportFile, report, StandardCharsets.UTF_8);
        RunStore.save(options.reports(), saved);
        if (real && options.saveBaseline() && runsCount > 0) {
            CostBaseline.write(options.baseline(), cost / runsCount, date);
            out.println("Stored today's mean cost as the baseline in " + options.baseline());
        }

        out.println(verdict);
        for (Gate gate : table.gates()) {
            out.println(String.format(
                    Locale.ROOT,
                    "  %-12s %s | measured %s | range %s | pass line %s",
                    label(gate.status()),
                    gate.name(),
                    gate.measured(),
                    gate.range(),
                    gate.passLine()));
        }
        out.println(costLine);
        out.println("Report: " + reportFile);
        return 0;
    }

    private static String label(Status status) {
        return switch (status) {
            case PASS -> "PASS";
            case FAIL -> "FAIL";
            case NOT_MEASURED -> "NOT MEASURED";
            case NOT_SET -> "NOT SET";
        };
    }

    private static Path pathOf(String value) {
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static SpendPrices prices(Settings settings) {
        return new SpendPrices(
                settings.get(SettingKey.DEEPGRAM_MICROS_PER_AUDIO_MINUTE),
                settings.get(SettingKey.GEMINI_INPUT_MICROS_PER_MILLION_TOKENS),
                settings.get(SettingKey.GEMINI_OUTPUT_MICROS_PER_MILLION_TOKENS),
                settings.get(SettingKey.GEMINI_WORST_CASE_INPUT_TOKENS),
                settings.get(SettingKey.GEMINI_WORST_CASE_OUTPUT_TOKENS));
    }

    private static CostEstimate estimate(LabelSet set, List<LabelSet.ClipInput> clips, int runs, SpendPrices prices)
            throws IOException {
        long labelledMs = 0;
        int takes = 0;
        if (set != null) {
            for (LabelSet.SpeakerFolder speaker : set.speakers()) {
                for (LabelSet.TakeInput take : speaker.takes()) {
                    labelledMs += length(take.audio());
                    takes++;
                }
            }
        }
        long clipMs = 0;
        for (LabelSet.ClipInput clip : clips) {
            clipMs += length(clip.audio());
        }
        return CostEstimate.of(labelledMs, takes, clipMs, clips.size(), runs, prices);
    }

    private static int length(Path audio) throws IOException {
        return TrustAudio.durationMs(Files.readAllBytes(audio))
                .orElseThrow(() -> new LabelSetException(audio.getFileName() + " is not a readable M4A file"));
    }

    private static FixTexts fixTexts() {
        try (InputStream in = Objects.requireNonNull(
                Cli.class.getResourceAsStream("/content-fixes/en.json"), "the fix texts are not on the class path")) {
            return FixTexts.parse(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    JsonMapper.builder().build());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long totalCost(HarnessRunner.Outcome outcome) {
        long total = 0;
        for (TakeCase take : allCases(outcome)) {
            for (Observation run : take.runs()) {
                total += run.costMicros();
            }
        }
        return total;
    }

    private static int runCount(HarnessRunner.Outcome outcome) {
        return allCases(outcome).stream().mapToInt(take -> take.runs().size()).sum();
    }

    private static List<TakeCase> allCases(HarnessRunner.Outcome outcome) {
        List<TakeCase> all = new ArrayList<>(outcome.labelled());
        all.addAll(outcome.replay());
        return all;
    }

    private static void deleteTree(Path folder) throws IOException {
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
