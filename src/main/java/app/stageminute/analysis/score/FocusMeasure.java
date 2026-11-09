package app.stageminute.analysis.score;

/** What a focus check measured, so the evidence's two numbers have a meaning (03 F-033 `evidence`). */
public enum FocusMeasure {
    /** Speech length in milliseconds. */
    SPEECH_MS,
    /** Relevance level, 1 to 4. */
    RELEVANCE_LEVEL,
    /** A step's status: missing 0, weak 1, strong 2. */
    STEP_STATUS,
    /** The longest recorded pause in milliseconds. */
    LONGEST_PAUSE_MS,
    /** Confirmed fillers a minute, rounded half up. */
    FILLERS_PER_MINUTE,
    /** When the first word starts, in milliseconds. */
    FIRST_WORD_MS,
    /** Words in the longest sentence. */
    LONGEST_SENTENCE_WORDS,
    /** Share of pace windows inside the band, in whole percent. */
    WINDOWS_IN_BAND_PERCENT,
    /** A Language level, 1 to 4. */
    LANGUAGE_LEVEL
}
