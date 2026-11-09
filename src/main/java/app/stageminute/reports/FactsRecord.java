package app.stageminute.reports;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The numbers and labels kept about one scored take ({@code facts_records}, D-11): what the next attempt's focus check
 * and the personal pace band read, so the server never takes them from the phone (03 F-027, F-033). No words: counts,
 * times, positions and fixed labels only.
 *
 * @param reportId the report it belongs to
 * @param sessionId the session of the take
 * @param attempt the attempt number, 1 to 3
 * @param style {@code prep} or {@code star}
 * @param durationMs the server-measured length
 * @param wordCount words heard
 * @param speechMs first word start to last word end
 * @param firstWordMs when the first word starts, or -1
 * @param midAnswerPauses pauses of 5 s or more
 * @param longestPauseMs the longest recorded pause, or 0
 * @param fillersConfirmed confirmed fillers
 * @param fillersPerMin confirmed fillers a minute
 * @param fillerClusters filler clusters
 * @param longestSentenceWords words in the longest sentence
 * @param runOnLabelled whether the call labelled a run-on
 * @param windowWpm pace of each 10 s window
 * @param paceBandLow the take's pace band, low edge
 * @param paceBandHigh the take's pace band, high edge
 * @param hitCap whether the take reached the time limit
 * @param steps the four step labels ({@code strong}, {@code weak}, {@code missing}) in the style's order
 * @param relevance relevance level
 * @param clarity clarity level
 * @param conciseness conciseness level
 * @param accuracy accuracy level
 * @param fixCode the fix this report put first
 * @param fixTarget the step the fix is about, or -1
 * @param fixCarried whether the fix was carried from an earlier attempt
 * @param fixNothingMajor whether the fix was the strong-take polish
 * @param focusResult {@code done}, {@code better} or {@code not_yet}, or null on a first attempt
 * @param averageWpm the take's average pace (for a later personal band)
 * @param scoreTotal the total
 * @param nextAttemptAllowed whether another attempt is offered
 */
public record FactsRecord(
        UUID reportId,
        UUID sessionId,
        int attempt,
        String style,
        int durationMs,
        int wordCount,
        int speechMs,
        int firstWordMs,
        int midAnswerPauses,
        int longestPauseMs,
        int fillersConfirmed,
        int fillersPerMin,
        int fillerClusters,
        int longestSentenceWords,
        boolean runOnLabelled,
        List<Integer> windowWpm,
        int paceBandLow,
        int paceBandHigh,
        boolean hitCap,
        List<String> steps,
        int relevance,
        int clarity,
        int conciseness,
        int accuracy,
        String fixCode,
        int fixTarget,
        boolean fixCarried,
        boolean fixNothingMajor,
        @Nullable String focusResult,
        int averageWpm,
        int scoreTotal,
        boolean nextAttemptAllowed) {

    public FactsRecord {
        Objects.requireNonNull(reportId, "reportId");
        Objects.requireNonNull(sessionId, "sessionId");
        windowWpm = List.copyOf(windowWpm);
        steps = List.copyOf(steps);
    }
}
