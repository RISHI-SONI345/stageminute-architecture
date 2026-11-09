package app.stageminute.reports;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * "Your patterns" ({@code GET /v1/me/patterns}, 03 F-056; 02b F-056): what the last first takes of one style show, as
 * numbers and step codes only. The card words come from the app's copy keys.
 *
 * @param style {@code prep} or {@code star}; the count restarts per style
 * @param takesCounted first takes counted so far, never more than {@code needed}
 * @param needed first takes needed before the cards show (the window, 5)
 * @param strength the step strong most often, when it is strong in enough takes; else null
 * @param opportunity the step weak or missing in enough takes; null when nothing keeps coming back
 * @param allStrong true when {@code needed} takes are counted and no step keeps coming back
 * @param fixedOnSecond second attempts of the counted sessions whose focus ended Done
 */
@Schema(name = "Patterns")
public record Patterns(
        String style,
        @JsonProperty("takes_counted") int takesCounted,
        int needed,
        @Nullable Strength strength,
        @Nullable Opportunity opportunity,
        @JsonProperty("all_strong") boolean allStrong,
        @JsonProperty("fixed_on_second") FixedOnSecond fixedOnSecond) {

    /**
     * The step that was strong most often.
     *
     * @param criterion a step code such as {@code point} or {@code result}
     * @param strongIn in how many of the counted takes it was strong
     */
    @Schema(name = "PatternStrength")
    public record Strength(
            String criterion, @JsonProperty("strong_in") int strongIn) {}

    /**
     * The step that keeps coming back weak or missing.
     *
     * @param step a step code
     * @param weakOrMissingIn in how many of the counted takes it was weak or missing
     */
    @Schema(name = "PatternOpportunity")
    public record Opportunity(
            String step, @JsonProperty("weak_or_missing_in") int weakOrMissingIn) {}

    /**
     * Second attempts that fixed the focus.
     *
     * @param count how many second attempts fixed the focus
     * @param days the account dates of those second attempts, oldest first
     */
    @Schema(name = "PatternFixedOnSecond")
    public record FixedOnSecond(int count, List<LocalDate> days) {}
}
