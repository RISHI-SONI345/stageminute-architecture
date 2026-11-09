package app.stageminute.analysis.score;

/**
 * The strongest line as positions and times (03 F-026 `strongest_line {sentence_id, quote, t0, t1, why}`). The quote
 * text and the reason are made by the pipeline from these positions: no speech is held here.
 *
 * @param sentenceId position of the sentence in the transcript's sentence list
 * @param firstWord position of its first word
 * @param lastWord position of its last word
 * @param startMs when it starts (t0)
 * @param endMs when it ends (t1)
 */
public record StrongestLineResult(int sentenceId, int firstWord, int lastWord, int startMs, int endMs) {}
