package app.stageminute.trust.run;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.trust.labelset.SyntheticSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The whole harness on the fake vendors and invented clips: nothing leaves this machine. */
class HarnessEndToEndTest {

    /** Words the fake speech to text "hears"; they must never reach a report, a saved run or the screen. */
    private static final List<String> FAKE_SPOKEN_WORDS =
            List.of("checkout", "caching", "customers", "shipped", "quietly", "rebuilt");

    @TempDir
    Path temp;

    private record Result(int code, String output) {}

    private Result cli(Map<String, String> env, String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int code = Cli.run(args, env, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return new Result(code, bytes.toString(StandardCharsets.UTF_8));
    }

    private Path reports() {
        return temp.resolve("reports");
    }

    private List<Path> files(String suffix) throws IOException {
        try (Stream<Path> list = Files.list(reports())) {
            return list.filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        }
    }

    private String latestReport() throws IOException {
        List<Path> reports = files(".md");
        return Files.readString(reports.get(reports.size() - 1));
    }

    @Test
    void aSyntheticRunPrintsEveryGateAndSaysLaunchBlocked() throws IOException {
        Result result = cli(Map.of(), "--synthetic", "--reports", reports().toString());

        assertThat(result.code()).isZero();
        assertThat(result.output()).contains("LAUNCH BLOCKED: SYNTHETIC RUN");
        for (String gate : List.of(
                "Filler precision",
                "Step-status agreement with annotators",
                "Run-to-run agreement of step status",
                "Grammar items raised on speech-recognition errors",
                "Fairness across accent groups: pace",
                "Fairness across accent groups: pause",
                "Fairness across accent groups: filler",
                "Repeat-run score spread",
                "Excellent share",
                "Latency of the labels-only call",
                "Cost per analysis")) {
            assertThat(result.output()).contains(gate);
        }
        String report = latestReport();
        assertThat(report).startsWith("# Trust harness report\n\n**LAUNCH BLOCKED: SYNTHETIC RUN");
        assertThat(report)
                .contains("kappa")
                .contains("Label-flip noise")
                .contains("Wait from upload to report: p50")
                .contains("Pace bands")
                .contains("Per-take diff against the last saved run")
                .contains("prompt_version: fake-prompt-0")
                .contains("`syn-01`")
                .contains("Left out (F-123: no valid consent): `syn-03` (consent revoked)")
                .contains("Promise tests");
        assertThat(files(".json")).hasSize(1);
    }

    @Test
    void nothingOfATakeReachesTheReportTheSavedRunOrTheScreen() throws IOException {
        Result result = cli(Map.of(), "--synthetic", "--reports", reports().toString());

        String everything = result.output()
                + latestReport()
                + Files.readString(files(".json").get(0));
        for (String word : FAKE_SPOKEN_WORDS) {
            assertThat(everything).doesNotContainIgnoringCase(word);
        }
    }

    @Test
    void theSecondRunIsComparedTakeByTakeWithTheFirst() throws IOException {
        cli(Map.of(), "--synthetic", "--reports", reports().toString());

        cli(Map.of(), "--synthetic", "--reports", reports().toString());

        assertThat(latestReport())
                .contains("Compared with the run of")
                .contains("same prompt, schema and model")
                .contains("takes changed");
    }

    @Test
    void aRunMadeWithAnotherPromptIsNotComparedUnlessTheCallerSaysSo() throws IOException {
        cli(Map.of(), "--synthetic", "--reports", reports().toString());
        pretendTheLastRunUsedAnotherPrompt();

        cli(Map.of(), "--synthetic", "--reports", reports().toString());
        assertThat(latestReport())
                .contains("Not compared: the versions differ")
                .contains("prompt_version older-prompt -> fake-prompt-0")
                .contains("older-prompt"); // the previous run's version is printed too

        pretendTheLastRunUsedAnotherPrompt();
        cli(Map.of(), "--synthetic", "--reports", reports().toString(), "--deliberate-comparison");
        assertThat(latestReport()).contains("DELIBERATE comparison across versions");
    }

    /** Rewrites the newest saved run as if an older prompt had made it (the file names sort by date). */
    private void pretendTheLastRunUsedAnotherPrompt() throws IOException {
        List<Path> saved = files(".json");
        Path latest = saved.get(saved.size() - 1);
        Files.writeString(latest, Files.readString(latest).replace("fake-prompt-0", "older-prompt"));
    }

    @Test
    void aRealRunWithoutYesSendsNothingAndPrintsTheEstimateFirst() throws IOException {
        SyntheticSet.write(temp.resolve("data"));
        Map<String, String> env = Map.of(
                "STAGEMINUTE_TRUST_SET",
                temp.resolve("data/set").toString(),
                "STAGEMINUTE_TRUST_REPLAY",
                temp.resolve("data/replay").toString(),
                "DEEPGRAM_API_KEY",
                "dg-secret-value-123",
                "GEMINI_API_KEY",
                "gm-secret-value-456");

        Result result = cli(env, "--real", "--reports", reports().toString());

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.output())
                .contains("ESTIMATE:")
                .contains("repeat-run test runs each clip 5 times")
                .contains("Nothing was sent")
                .contains("--yes")
                .doesNotContain("dg-secret-value-123")
                .doesNotContain("gm-secret-value-456");
        assertThat(Files.exists(reports())).isFalse();
    }

    @Test
    void theEstimateAloneNeverRuns() throws IOException {
        SyntheticSet.write(temp.resolve("data"));
        Map<String, String> env =
                Map.of("STAGEMINUTE_TRUST_REPLAY", temp.resolve("data/replay").toString());

        Result result =
                cli(env, "--estimate", "--runs", "3", "--reports", reports().toString());

        assertThat(result.code()).isZero();
        assertThat(result.output()).contains("ESTIMATE: 6 pipeline runs").contains("each clip 3 times");
        assertThat(Files.exists(reports())).isFalse();
    }

    @Test
    void aRealRunWithYesStopsAndNamesAMissingKeyWithoutPrintingAnyValue() {
        SyntheticSet.write(temp.resolve("data"));
        Map<String, String> env = Map.of(
                "STAGEMINUTE_TRUST_SET", temp.resolve("data/set").toString(),
                "STAGEMINUTE_TRUST_REPLAY", temp.resolve("data/replay").toString(),
                "DEEPGRAM_API_KEY", "dg-secret-value-123");

        Result result = cli(env, "--real", "--yes", "--reports", reports().toString());

        assertThat(result.code()).isEqualTo(1);
        assertThat(result.output()).contains("GEMINI_API_KEY is not set").doesNotContain("dg-secret-value-123");
        assertThat(Files.exists(reports())).isFalse();
    }

    @Test
    void badArgumentsPrintTheUsageAndStop() {
        assertThat(cli(Map.of(), "--bogus").code()).isEqualTo(1);
        assertThat(cli(Map.of(), "--bogus").output()).contains("Unknown option").contains("Usage:");
        assertThat(cli(Map.of()).output()).contains("Choose one of");
        assertThat(cli(Map.of(), "--synthetic", "--runs", "0").output()).contains("--runs must be 1 or more");
        assertThat(cli(Map.of(), "--synthetic", "--runs").output()).contains("needs a value");
        assertThat(cli(Map.of(), "--fake").output()).contains("STAGEMINUTE_TRUST_SET");
    }

    @Test
    void aSpeakerCanBeDeletedWithTheirFolderConsentSavedRowsAndReportLines() throws IOException {
        SyntheticSet.write(temp.resolve("data"));
        Path set = temp.resolve("data/set");
        Map<String, String> env = Map.of(
                "STAGEMINUTE_TRUST_SET", set.toString(),
                "STAGEMINUTE_TRUST_REPLAY", temp.resolve("data/replay").toString());
        cli(env, "--fake", "--reports", reports().toString());
        assertThat(latestReport()).contains("| `syn-01` |");
        assertThat(Files.readString(files(".json").get(0))).contains("syn-01/take-1.m4a");

        Result result =
                cli(env, "--delete-speaker", "syn-01", "--reports", reports().toString());

        assertThat(result.code()).isZero();
        assertThat(result.output()).contains("Deleted speaker syn-01").contains("consent marked deleted");
        assertThat(Files.exists(set.resolve("syn-01"))).isFalse();
        assertThat(Files.readString(set.resolve("consents.json")))
                .contains("\"SYN-CONSENT-1\":\"deleted\"")
                .contains("\"SYN-CONSENT-2\":\"active\"");
        assertThat(latestReport()).doesNotContain("`syn-01`");
        assertThat(Files.readString(files(".json").get(0))).doesNotContain("syn-01/");
        assertThat(Files.readString(files(".json").get(0))).contains("syn-02/take-1.m4a");

        Result again = cli(env, "--fake", "--reports", reports().toString());
        assertThat(again.code()).isZero();
        assertThat(latestReport()).doesNotContain("`syn-01`").contains("`syn-02`");
    }

    @Test
    void deletionRefusesAPathAndAnUnknownSpeaker() {
        SyntheticSet.write(temp.resolve("data"));
        Map<String, String> env =
                Map.of("STAGEMINUTE_TRUST_SET", temp.resolve("data/set").toString());

        assertThat(cli(env, "--delete-speaker", "../replay").output()).contains("plain folder name");
        assertThat(cli(env, "--delete-speaker", "nobody").output()).contains("no speaker folder with that id");
        assertThat(cli(Map.of(), "--delete-speaker", "syn-01").output()).contains("STAGEMINUTE_TRUST_SET is not set");
        assertThat(Files.exists(temp.resolve("data/replay"))).isTrue();
    }
}
