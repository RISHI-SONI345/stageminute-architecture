package app.stageminute.analysis.score;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The take map and the fix's place on it (03 F-026 `moments[]` and `fix_first.moment`).
 *
 * @param moments up to the maximum number of moments, in time order
 * @param fixMoment the place the first fix is about, when it has one; always one of {@code moments}
 */
public record TakeMoments(List<Moment> moments, Optional<Moment> fixMoment) {

    public TakeMoments {
        Objects.requireNonNull(fixMoment, "fixMoment");
        moments = List.copyOf(moments);
    }
}
