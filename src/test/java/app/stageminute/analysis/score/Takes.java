package app.stageminute.analysis.score;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Test fixture: a {@link TakeFacts} that starts as a strong, ordinary one-minute PREP take and changes one thing per
 * test, so every table row shows which fact makes the fix fire. Starting values:
 *
 * <ul>
 *   <li>PREP, all four steps strong, relevance 4, Language 4/4/4
 *   <li>120 words, 55 s of speech in a 58 s take, first word at 0.8 s
 *   <li>no mid-answer pauses, no fillers, longest sentence 20 words, no run-on label
 *   <li>six pace windows at 140 words a minute (5 full and one of 8 s), launch pace band, did not hit the cap
 * </ul>
 */
final class Takes {

    private Style style = Style.PREP;
    private final List<StepStatus> steps =
            new ArrayList<>(List.of(StepStatus.STRONG, StepStatus.STRONG, StepStatus.STRONG, StepStatus.STRONG));
    private int relevance = 4;
    private LanguageLevels language = new LanguageLevels(4, 4, 4);
    private int wordCount = 120;
    private int speechMs = 55_000;
    private int durationMs = 58_000;
    private int firstWordMs = 800;
    private int midAnswerPauses = 0;
    private int longestPauseMs = 0;
    private int confirmedFillers = 0;
    private int fillerClusters = 0;
    private int longestSentenceWords = 20;
    private boolean runOnLabelled = false;
    private List<Integer> windows = List.of(140, 140, 140, 140, 140, 140);
    private PaceBand band = PaceBand.LAUNCH_DEFAULT;
    private boolean hitCap = false;

    private Takes() {}

    static Takes strong() {
        return new Takes();
    }

    Takes star() {
        this.style = Style.STAR;
        return this;
    }

    /** Sets step {@code index}: 0 Point or Situation, 1 Reason or Task, 2 Example or Action, 3 Closing or Result. */
    Takes step(int index, StepStatus status) {
        steps.set(index, status);
        return this;
    }

    Takes relevance(int level) {
        this.relevance = level;
        return this;
    }

    Takes language(int clarity, int conciseness, int accuracy) {
        this.language = new LanguageLevels(clarity, conciseness, accuracy);
        return this;
    }

    Takes words(int count) {
        this.wordCount = count;
        return this;
    }

    Takes speechMs(int ms) {
        this.speechMs = ms;
        return this;
    }

    Takes durationMs(int ms) {
        this.durationMs = ms;
        return this;
    }

    Takes firstWordMs(int ms) {
        this.firstWordMs = ms;
        return this;
    }

    Takes pauses(int midAnswer, int longestMs) {
        this.midAnswerPauses = midAnswer;
        this.longestPauseMs = longestMs;
        return this;
    }

    Takes fillers(int confirmed, int clusters) {
        this.confirmedFillers = confirmed;
        this.fillerClusters = clusters;
        return this;
    }

    Takes longestSentence(int words, boolean labelledRunOn) {
        this.longestSentenceWords = words;
        this.runOnLabelled = labelledRunOn;
        return this;
    }

    Takes windows(int... wpm) {
        this.windows = Arrays.stream(wpm).boxed().toList();
        return this;
    }

    Takes band(int low, int high) {
        this.band = new PaceBand(low, high);
        return this;
    }

    Takes hitCap() {
        this.hitCap = true;
        return this;
    }

    TakeFacts build() {
        return new TakeFacts(
                style,
                steps,
                relevance,
                language,
                wordCount,
                speechMs,
                durationMs,
                firstWordMs,
                midAnswerPauses,
                longestPauseMs,
                confirmedFillers,
                fillerClusters,
                longestSentenceWords,
                runOnLabelled,
                windows,
                band,
                hitCap);
    }
}
