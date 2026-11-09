package app.stageminute.trust.run;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.analysis.score.Style;
import app.stageminute.trust.labelset.LabelSet;
import app.stageminute.trust.labelset.SyntheticSet;
import app.stageminute.trust.model.Observation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The harness waits out a refused-by-the-breaker run and runs it again, instead of recording it as a failure. */
class BreakerWaitTest {

    private static Observation answer(boolean scored, String status) {
        return new Observation(
                scored,
                status,
                List.of(),
                50,
                List.of(),
                List.of(),
                List.of(),
                Optional.empty(),
                0,
                0,
                List.of(),
                0,
                10,
                0,
                Optional.empty());
    }

    private static List<LabelSet.ClipInput> oneClip(Path dir) throws Exception {
        Path audio = dir.resolve("clip-1.m4a");
        Files.write(audio, SyntheticSet.m4a(50_000));
        return List.of(new LabelSet.ClipInput("clip-1.m4a", audio, Style.PREP, "A topic.", 60_000));
    }

    @Test
    void aRunTheBreakerRefusedIsWaitedForAndRunAgain(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<Long> pauses = new ArrayList<>();
        TakeRunner runner = (audio, style, topic, cap) ->
                calls.incrementAndGet() < 3 ? answer(false, "vendor_unavailable:breaker_open") : answer(true, "ok");

        var outcome = HarnessRunner.execute(null, oneClip(dir), 1, runner, pauses::add);

        assertThat(calls).hasValue(3);
        assertThat(pauses).containsExactly(HarnessRunner.BREAKER_WAIT_MS, HarnessRunner.BREAKER_WAIT_MS);
        assertThat(outcome.replay().get(0).runs())
                .singleElement()
                .extracting(Observation::scored)
                .isEqualTo(true);
    }

    @Test
    void anotherFailureIsKeptAsItIsAndNotRetried(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TakeRunner runner = (audio, style, topic, cap) -> {
            calls.incrementAndGet();
            return answer(false, "vendor_unavailable:llm_timeout");
        };

        var outcome = HarnessRunner.execute(null, oneClip(dir), 1, runner, millis -> {
            throw new AssertionError("must not wait");
        });

        assertThat(calls).hasValue(1);
        assertThat(outcome.replay().get(0).runs().get(0).status()).isEqualTo("vendor_unavailable:llm_timeout");
    }

    @Test
    void itGivesUpAfterThreeWaits(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TakeRunner runner = (audio, style, topic, cap) -> {
            calls.incrementAndGet();
            return answer(false, "vendor_unavailable:breaker_open");
        };

        HarnessRunner.execute(null, oneClip(dir), 1, runner, millis -> {});

        assertThat(calls).hasValue(4);
    }
}
