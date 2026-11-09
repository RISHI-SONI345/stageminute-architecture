package app.stageminute.trust.run;

import app.stageminute.analysis.score.Style;
import app.stageminute.trust.model.Observation;

/** Runs one recording through the pipeline once and says what came out. */
@FunctionalInterface
public interface TakeRunner {

    /**
     * Runs one recording and reports what came out.
     *
     * @param audio the AAC/M4A bytes; held in memory for this call only
     * @param style PREP or STAR
     * @param topic the topic text sent to the judge
     * @param capMs the topic's speaking limit
     */
    @SuppressWarnings("ArrayRecordComponent")
    Observation run(byte[] audio, Style style, String topic, int capMs);
}
