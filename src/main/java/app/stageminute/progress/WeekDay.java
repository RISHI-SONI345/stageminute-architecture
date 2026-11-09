package app.stageminute.progress;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** One dot of the week strip: the account date, how it looks, and whether it is the account's today. */
@Schema(name = "ProgressWeekDay")
public record WeekDay(
        LocalDate date,
        DayState state,

        @JsonProperty("is_today") boolean today) {}
