package app.stageminute.analysis.score;

import java.util.List;

/**
 * What code measured from a take (03 F-027). Numbers only: no words, no filler words, no mis-heard words (root
 * CLAUDE.md, privacy rules; D-11). Run-on sentences are listed by position, not by text.
 *
 * @param wordCount words Deepgram heard
 * @param durationMs length of the take
 * @param firstWordMs when the first word starts, or -1 when there are no words
 * @param pausesRecorded gaps of 1.2 s or more between words
 * @param midAnswerPauses recorded pauses of 5 s or more (silence before the first word and after the last is not one)
 * @param longestPauseMs the longest recorded pause, or 0 when there is none
 * @param windowWpm pace of each 10 s window in words a minute
 * @param lowConfidenceWords words with confidence under 0.5
 * @param habitMarkerCount sentences that start with So, Okay or Right
 * @param habitMarkers whether that count reaches the habit line (3)
 * @param runOnSentences positions of sentences over 40 words (candidates; the model labels them)
 * @param articulationWpm words a minute over speaking time (first word start to last word end, minus recorded pauses)
 * @param averageWpm words a minute from first word start to last word end; the number a later personal band reads
 * @param outsideBandMs total time of the pace windows outside the 85 to 240 band
 * @param paceFlag whether {@code outsideBandMs} reaches 30 s
 * @param fillerCandidates places that may be fillers, by position (no text)
 * @param misheardWords positions of up to 3 low-confidence content words, never quoted
 * @param speechMs length of the speech: first word start to last word end (03 F-032 "20 s of speech"), or 0 when there
 *     are no words
 * @param longestSentenceWords word count of the longest sentence, or 0 when there are none (03 F-032 `split_sentence`)
 */
public record SpeechFacts(
        int wordCount,
        int durationMs,
        int firstWordMs,
        int pausesRecorded,
        int midAnswerPauses,
        int longestPauseMs,
        List<Integer> windowWpm,
        int lowConfidenceWords,
        int habitMarkerCount,
        boolean habitMarkers,
        List<Integer> runOnSentences,
        int articulationWpm,
        int averageWpm,
        int outsideBandMs,
        boolean paceFlag,
        List<FillerCandidate> fillerCandidates,
        List<Integer> misheardWords,
        int speechMs,
        int longestSentenceWords) {

    public SpeechFacts {
        windowWpm = List.copyOf(windowWpm);
        runOnSentences = List.copyOf(runOnSentences);
        fillerCandidates = List.copyOf(fillerCandidates);
        misheardWords = List.copyOf(misheardWords);
    }
}
