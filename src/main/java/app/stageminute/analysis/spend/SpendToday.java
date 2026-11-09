package app.stageminute.analysis.spend;

/**
 * What today's confirmed AI spend looks like, for the trust harness and the admin console (03 F-087, 06 section 4: AI
 * cost per day and per analysis). One analysis makes one Deepgram call and one Gemini call (D-84), so the cost of an
 * analysis is about twice {@link #meanMicrosPerCall()} when nothing was retried.
 *
 * @param confirmedMicros millionths of a dollar the vendors reported using today
 * @param calls answered vendor calls today (a retry that was answered counts)
 * @param meanMicrosPerCall {@code confirmedMicros / calls}, or 0 with no calls
 */
public record SpendToday(long confirmedMicros, int calls, long meanMicrosPerCall) {

    static SpendToday of(SpendStore.Day day) {
        return new SpendToday(day.micros(), day.calls(), day.calls() == 0 ? 0 : day.micros() / day.calls());
    }
}
