package app.stageminute.analysis.score;

/**
 * A place in the take that may be a filler. It holds positions and times, never the words, so it carries no speech
 * (root CLAUDE.md, privacy rules: no filler words kept on the server).
 *
 * @param id position in the candidate list, used by the AI call to label it
 * @param kind always counted, judged in context, or a hedge
 * @param firstWord position of the first word in the transcript
 * @param lastWord position of the last word (the same as the first for one word)
 * @param startMs when the candidate starts, in milliseconds from the start of the take
 */
public record FillerCandidate(int id, FillerKind kind, int firstWord, int lastWord, int startMs) {}
