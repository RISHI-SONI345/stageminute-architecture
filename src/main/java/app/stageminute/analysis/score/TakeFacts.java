package app.stageminute.analysis.score;

import java.util.List;
import java.util.Objects;

/**
 * Everything the Fix Library and the focus check read about one take: the call's labels and the code's counts. This
 * is what the server's {@code facts_record} keeps (numbers and labels, no words; D-11), so the previous attempt's
 * facts are read from there and never from the client (03 F-033). No user, no plan, no clock.
 *
 * @param style PREP or STAR
 * @param steps the four step labels in the style's order
 * @param relevanceLevel relevance label, 1 (off topic) to 4
 * @param language the three Language labels
 * @param wordCount words heard
 * @param speechMs length of the speech: first word start to last word end ({@link SpeechFacts#speechMs()})
 * @param durationMs length of the take
 * @param firstWordMs when the first word starts, or -1 when there are no words
 * @param midAnswerPauses pauses of 5 s or more in the middle of the answer
 * @param longestPauseMs the longest recorded pause, 0 when there is none
 * @param confirmedFillers confirmed fillers in the take ({@link FillerFacts#confirmed()})
 * @param fillerClusters confirmed filler clusters ({@link FillerFacts#clusterCount()})
 * @param longestSentenceWords word count of the longest sentence
 * @param runOnLabelled whether the call labelled a long sentence a run-on
 * @param windowWpm pace of each 10 s window in words a minute
 * @param paceBand the pace band for this take (launch default or the person's own)
 * @param hitCap whether the take stopped at the time cap
 */
public record TakeFacts(
        Style style,
        List<StepStatus> steps,
        int relevanceLevel,
        LanguageLevels language,
        int wordCount,
        int speechMs,
        int durationMs,
        int firstWordMs,
        int midAnswerPauses,
        int longestPauseMs,
        int confirmedFillers,
        int fillerClusters,
        int longestSentenceWords,
        boolean runOnLabelled,
        List<Integer> windowWpm,
        PaceBand paceBand,
        boolean hitCap) {

    private static final int STEPS = 4;

    public TakeFacts {
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(paceBand, "paceBand");
        if (steps.size() != STEPS) {
            throw new IllegalArgumentException("a take has " + STEPS + " steps, was " + steps.size());
        }
        if (durationMs <= 0) {
            throw new IllegalArgumentException("durationMs must be positive, was " + durationMs);
        }
        if (wordCount < 0
                || speechMs < 0
                || firstWordMs < -1
                || midAnswerPauses < 0
                || longestPauseMs < 0
                || confirmedFillers < 0
                || fillerClusters < 0
                || longestSentenceWords < 0) {
            throw new IllegalArgumentException("counts and times cannot be negative");
        }
        steps = List.copyOf(steps);
        windowWpm = List.copyOf(windowWpm);
    }

    /**
     * Time in pace windows outside the take's pace band. Windows are {@code windowMs} long, except a last partial
     * window, which counts for its own length (03 F-027 pace windows). Both band edges count as inside.
     */
    int outsideBandMs(int windowMs) {
        int fullWindows = durationMs / windowMs;
        int remainderMs = durationMs % windowMs;
        int outside = 0;
        for (int i = 0; i < windowWpm.size(); i++) {
            if (!paceBand.contains(windowWpm.get(i))) {
                outside += i < fullWindows ? windowMs : remainderMs;
            }
        }
        return outside;
    }

    /** Number of pace windows inside the take's pace band. */
    int windowsInBand() {
        return (int) windowWpm.stream().filter(paceBand::contains).count();
    }
}
