package app.stageminute.trust.measures;

import app.stageminute.trust.model.Observation;
import app.stageminute.trust.model.TakeCase;
import java.util.List;
import java.util.Optional;

/** The server-measured take length against the phone's {@code duration_ms} (C-69), where the phone value is known. */
public final class TakeLength {

    private TakeLength() {}

    /**
     * The difference.
     *
     * @param compared takes that have both values
     * @param meanAbsDiffMs mean of the absolute differences, or NaN when none was compared
     * @param maxAbsDiffMs the largest absolute difference (0 when none was compared)
     * @param noPhoneValue takes with no phone value
     */
    public record Result(int compared, double meanAbsDiffMs, int maxAbsDiffMs, int noPhoneValue) {}

    public static Result of(List<TakeCase> cases) {
        int compared = 0;
        long sum = 0;
        int max = 0;
        int missing = 0;
        for (TakeCase take : cases) {
            Optional<Observation> run = take.runs().stream().findFirst();
            if (run.isEmpty()) {
                continue;
            }
            if (take.phoneDurationMs().isEmpty()) {
                missing++;
                continue;
            }
            int diff = Math.abs(
                    run.get().measuredDurationMs() - take.phoneDurationMs().getAsInt());
            compared++;
            sum += diff;
            max = Math.max(max, diff);
        }
        return new Result(compared, compared == 0 ? Double.NaN : (double) sum / compared, max, missing);
    }
}
