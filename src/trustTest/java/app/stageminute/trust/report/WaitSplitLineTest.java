package app.stageminute.trust.report;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.trust.measures.WaitTimes;
import org.junit.jupiter.api.Test;

/** The report line that splits the wait into the Gemini call and the rest. */
class WaitSplitLineTest {

    @Test
    void theLineNamesBothPartsAndSaysDeepgramFailuresAreLeftOut() {
        String line = ReportWriter.waitSplitLine(new WaitTimes.Result(11.4, 24.7, 25, 6.5, 8.0, 20, 4.9, 16.0));

        assertThat(line)
                .contains("Gemini labels call alone: p50 6.5 s, p95 8.0 s over 20 runs")
                .contains("(Deepgram, scoring, our code): p50 4.9 s, p95 16.0 s")
                .contains("never reached Gemini");
    }

    @Test
    void withNoTimedRunTheLineSaysSo() {
        assertThat(ReportWriter.waitSplitLine(new WaitTimes.Result(1.0, 2.0, 3)))
                .contains("not timed");
    }
}
