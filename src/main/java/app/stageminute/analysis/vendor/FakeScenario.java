package app.stageminute.analysis.vendor;

import java.util.Locale;

/**
 * What the fake vendors play (10 section 3, step 2: the pipeline runs end to end on fakes first). One scenario per
 * report status, plus a vendor failure. Chosen by the dev setting {@code stageminute.analysis.fake-scenario}.
 */
public enum FakeScenario {
    /** About 144 words a minute, four steps heard, one hedge, no long pause: a scored take. */
    OK,
    /** Fewer than 40 words: no score. */
    SHORT_TAKE,
    /** One word in five heard with low confidence (20 %): scored, with the "hard to hear" caution. */
    LOW_AUDIO,
    /** Words heard, but not English: no score. */
    NOT_ENGLISH,
    /** No words at all: no score. */
    NO_SPEECH,
    /** Speech to text does not answer: 502 and the session is given back. */
    VENDOR_FAILURE;

    /** Reads the setting's value ({@code ok}, {@code low_audio} ...); anything else is {@link #OK}. */
    public static FakeScenario of(String value) {
        for (FakeScenario scenario : values()) {
            if (scenario.name().equalsIgnoreCase(value.trim().replace('-', '_'))) {
                return scenario;
            }
        }
        return OK;
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
