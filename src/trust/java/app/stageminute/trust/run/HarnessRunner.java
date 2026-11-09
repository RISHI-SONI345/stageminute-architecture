package app.stageminute.trust.run;

import app.stageminute.trust.labelset.LabelSet;
import app.stageminute.trust.model.Observation;
import app.stageminute.trust.model.TakeCase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Feeds the recordings through a {@link TakeRunner}: every labelled take once, every replay clip several times (the
 * repeat-run test). Audio is read into memory for one call at a time and dropped; nothing is copied anywhere.
 */
public final class HarnessRunner {

    private HarnessRunner() {}

    /**
     * What the run produced.
     *
     * @param labelled one case per labelled take, with its annotators' labels and one run
     * @param replay one case per replay clip, with all its runs
     * @param excluded speakers left out for a missing or revoked consent
     */
    public record Outcome(List<TakeCase> labelled, List<TakeCase> replay, List<LabelSet.Excluded> excluded) {}

    /**
     * Runs every labelled take once and every clip {@code runs} times.
     *
     * @param set the labelled set, or null to run only the replay clips
     * @param clips the replay clips (may be empty)
     * @param runs how many times each clip runs (the gate says 5)
     */
    public static Outcome execute(LabelSet set, List<LabelSet.ClipInput> clips, int runs, TakeRunner runner) {
        return execute(set, clips, runs, runner, Thread::sleep);
    }

    /** How long the harness waits when the server's own circuit breaker is open: its open time (30 s) and a second. */
    static final long BREAKER_WAIT_MS = 31_000;

    private static final int BREAKER_RETRIES = 3;

    /** Sleeps for a number of milliseconds; a test passes one that does not wait. */
    @FunctionalInterface
    interface Pause {
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * A run the breaker refused without trying (it opens after a few slow or failed calls) says nothing about the
     * take, so the harness waits for the breaker to close and runs it again, up to three times. Other failures are
     * kept as they are.
     */
    private static Observation runPatiently(
            TakeRunner runner,
            Pause pause,
            byte[] audio,
            app.stageminute.analysis.score.Style style,
            String topic,
            int capMs) {
        Observation run = runner.run(audio, style, topic, capMs);
        for (int attempt = 0; attempt < BREAKER_RETRIES && run.status().endsWith(":breaker_open"); attempt++) {
            try {
                pause.sleep(BREAKER_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return run;
            }
            run = runner.run(audio, style, topic, capMs);
        }
        return run;
    }

    static Outcome execute(LabelSet set, List<LabelSet.ClipInput> clips, int runs, TakeRunner runner, Pause pause) {
        List<TakeCase> labelled = new ArrayList<>();
        List<LabelSet.Excluded> excluded = new ArrayList<>();
        if (set != null) {
            excluded.addAll(set.excluded());
            for (LabelSet.SpeakerFolder speaker : set.speakers()) {
                for (LabelSet.TakeInput take : speaker.takes()) {
                    Observation run = runPatiently(
                            runner, pause, read(take.audio()), speaker.style(), speaker.topic(), speaker.capMs());
                    labelled.add(new TakeCase(
                            speaker.id(),
                            speaker.accentGroup(),
                            speaker.style().name(),
                            take.name(),
                            take.references(),
                            List.of(run),
                            take.phoneDurationMs()));
                }
            }
        }
        List<TakeCase> replay = new ArrayList<>();
        for (LabelSet.ClipInput clip : clips) {
            byte[] audio = read(clip.audio());
            List<Observation> observations = new ArrayList<>();
            for (int i = 0; i < runs; i++) {
                observations.add(runPatiently(runner, pause, audio, clip.style(), clip.topic(), clip.capMs()));
            }
            replay.add(new TakeCase(
                    "replay", "none", clip.style().name(), clip.name(), List.of(), observations, OptionalInt.empty()));
        }
        return new Outcome(labelled, replay, excluded);
    }

    private static byte[] read(java.nio.file.Path audio) {
        try {
            return Files.readAllBytes(audio);
        } catch (IOException e) {
            throw new UncheckedIOException("a recording cannot be read: " + audio.getFileName(), e);
        }
    }
}
