package app.stageminute.progress;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The streak block (03 F-053): everything the Progress card shows, worked out by the server. Days, never sessions
 * (F-120).
 *
 * @param current days in a row that count, today included when today counts; frozen days keep the chain alive and are
 *     not counted
 * @param best the best streak ever, never lower than it was
 * @param practicedToday whether something counted on the account's today
 * @param weekDays the Monday-to-Sunday week that holds today
 * @param state the card to show
 * @param endedLength with {@code streak_ended}: how many days the streak that ended had
 * @param nextStreak with {@code practice_today}: the number the person makes by practicing now
 * @param milestonePending the milestone (in days) to celebrate next, or null
 */
@Schema(name = "ProgressStreak")
@JsonInclude(JsonInclude.Include.ALWAYS)
public record StreakView(
        int current,
        int best,

        @JsonProperty("practiced_today") boolean practicedToday,

        @JsonProperty("week_days") List<WeekDay> weekDays,
        StreakState state,

        @JsonProperty("ended_length") @Nullable @Schema(nullable = true)
        Integer endedLength,

        @JsonProperty("next_streak") @Nullable @Schema(nullable = true)
        Integer nextStreak,

        @JsonProperty("milestone_pending") @Nullable @Schema(nullable = true)
        Integer milestonePending) {

    public StreakView {
        weekDays = List.copyOf(weekDays);
    }

    public StreakView withMilestonePending(@Nullable Integer days) {
        return new StreakView(current, best, practicedToday, weekDays, state, endedLength, nextStreak, days);
    }
}
