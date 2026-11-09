package app.stageminute.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Speaker recordings, the annotators' labels, the consent list and every report or saved run never enter git (F-123,
 * tests/CLAUDE.md, root rule 4). This reads the index (tracked and staged files) and asks git whether the ignore rules
 * really cover the places the harness writes to. It is skipped only when git itself is not available.
 */
class NoPersonalDataInGitTest {

    private static final Pattern AUDIO = Pattern.compile(".*\\.(m4a|wav|mp3|aac|ogg|flac|webm)$");

    private static Path repo() {
        String root = System.getProperty("stageminute.repo.root");
        assumeTrue(root != null, "no repository root given");
        return Path.of(root);
    }

    private static List<String> git(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        assumeTrue(code == 0 || args[0].equals("check-ignore"), "git is not available here");
        return output.lines().filter(line -> !line.isBlank()).toList();
    }

    @Test
    void noRecordingLabelConsentOrReportUnderTestsTrustIsTrackedOrStaged() throws Exception {
        List<String> tracked = git(repo(), "ls-files", "--", "tests/trust");

        assertThat(tracked)
                .as("files under tests/trust that must never be in git")
                .noneMatch(file -> AUDIO.matcher(file.toLowerCase(Locale.ROOT)).matches())
                .noneMatch(file -> file.contains("/labels-"))
                .noneMatch(file -> file.endsWith("/speaker.json"))
                .noneMatch(file -> file.endsWith("/consents.json"))
                .noneMatch(file -> file.contains("/reports/"));
    }

    @Test
    void theIgnoreRulesCoverEveryPlaceTheHarnessAndTheFounderPutPersonalData() throws Exception {
        Path repo = repo();
        List<String> places = List.of(
                "tests/trust/reports/report-2026-11-02T00-00-00Z-real.md",
                "tests/trust/reports/run-2026-11-02T00-00-00Z-real.json",
                "tests/trust/set/speaker-01/take-1.m4a",
                "tests/trust/set/speaker-01/labels-a1.json",
                "tests/trust/set/speaker-01/speaker.json",
                "tests/trust/set/consents.json",
                "tests/trust/replay/clip-1.m4a",
                "tests/trust/elsewhere/take.m4a");

        List<String> arguments = new java.util.ArrayList<>(List.of("check-ignore", "--no-index", "--"));
        arguments.addAll(places);
        List<String> ignored = git(repo, arguments.toArray(new String[0]));

        assertThat(ignored).containsExactlyInAnyOrderElementsOf(places);
    }

    @Test
    void theCostBaselineIsNotIgnoredBecauseItHoldsNoPersonalData() throws Exception {
        List<String> ignored = git(repo(), "check-ignore", "--no-index", "--", "tests/trust/cost-baseline.json");

        assertThat(ignored).isEmpty();
    }
}
