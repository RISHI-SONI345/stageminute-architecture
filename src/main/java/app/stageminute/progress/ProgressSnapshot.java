package app.stageminute.progress;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * Everything the Progress tab shows from the server's numbers in one answer (03 F-053, F-055): the phone does no date
 * logic and computes nothing. Never reads the person's plan (the score path and the streak path ignore the tier).
 *
 * @param todayLocalDate the account's today: the day the week strip points at (never earlier than the latest day that
 *     already counted, so a zone change west cannot bring back a day, F-050)
 * @param streak the streak block
 * @param totals the totals line
 */
@Schema(name = "ProgressSnapshot")
public record ProgressSnapshot(
        @JsonProperty("today_local_date") LocalDate todayLocalDate, StreakView streak, Totals totals) {}
