package app.stageminute.analysis.score;

import java.util.List;
import java.util.Optional;

/**
 * Chooses the pace word of a take (step3/CONFLICTS.md C-65) from its 10 s pace windows and its own pace band (the
 * launch band or the personal one). Steady when enough windows are inside the band; otherwise a bit fast or a bit slow
 * when the windows outside lean one way, else uneven. Both band edges count as inside. Pure: no clock, no user, no
 * Spring. It gives no points and never changes the score (it is not read by {@link ScoreBuilder}).
 */
public final class PaceWords {

    private PaceWords() {}

    /** Empty when the take has no pace window (a take with no score has no word). */
    public static Optional<PaceWord> of(List<Integer> windowWpm, PaceBand band, PaceWordSettings settings) {
        int total = windowWpm.size();
        if (total == 0) {
            return Optional.empty();
        }
        int fast = 0;
        int slow = 0;
        for (int wpm : windowWpm) {
            if (wpm > band.highWpm()) {
                fast++;
            } else if (wpm < band.lowWpm()) {
                slow++;
            }
        }
        int inside = total - fast - slow;
        if (inside * 100L >= (long) settings.steadyInBandPercent() * total) {
            return Optional.of(PaceWord.STEADY);
        }
        int outside = fast + slow;
        if (fast * 100L >= (long) settings.oneSidePercent() * outside) {
            return Optional.of(PaceWord.A_BIT_FAST);
        }
        if (slow * 100L >= (long) settings.oneSidePercent() * outside) {
            return Optional.of(PaceWord.A_BIT_SLOW);
        }
        return Optional.of(PaceWord.UNEVEN);
    }
}
