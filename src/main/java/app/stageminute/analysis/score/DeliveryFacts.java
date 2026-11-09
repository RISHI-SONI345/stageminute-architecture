package app.stageminute.analysis.score;

import java.util.List;

/**
 * The numbers the Delivery part is built from. Counts and whole numbers only: no words, no audio.
 *
 * @param midAnswerPauses pauses of 5 s or more in the middle of the answer
 * @param fillerClusters confirmed filler clusters (3 or more confirmed fillers in 10 s)
 * @param confirmedFillers confirmed fillers in the whole take
 * @param durationMs length of the take in milliseconds
 * @param windowWpm pace of each 10 s window in words a minute (words in the window x 6)
 */
public record DeliveryFacts(
        int midAnswerPauses, int fillerClusters, int confirmedFillers, int durationMs, List<Integer> windowWpm) {

    public DeliveryFacts {
        if (windowWpm == null || windowWpm.isEmpty()) {
            throw new IllegalArgumentException("a take needs at least one pace window");
        }
        windowWpm = List.copyOf(windowWpm);
    }
}
