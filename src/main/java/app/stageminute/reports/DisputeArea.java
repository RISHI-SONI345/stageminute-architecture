package app.stageminute.reports;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Locale;

/**
 * What a score dispute is about (02b F-042; C-67 for {@link #FIX_FIRST}): a closed list of codes, no words (AD-147).
 * A step or a language suggestion is named by a number in the dispute's {@code index}: the step's position (0 to 3,
 * the order of {@code findings.steps}) or the suggestion's {@code id} (in {@code findings.language_items} or {@code
 * findings.swaps}). Every other area takes no index.
 */
@Schema(name = "DisputeArea")
public enum DisputeArea {
    /** A step in Structure; {@code index} is the step's position. */
    STRUCTURE_STEP,
    /** A suggestion in Language; {@code index} is the suggestion's id. */
    LANGUAGE_SUGGESTION,
    PAUSE,
    PACE,
    FILLER,
    /** The score as a whole. */
    SCORE,
    /** Something else. */
    OTHER,
    /** The one-tap "This fix felt wrong" on the Fix card (C-67): never a note, never a transcript. */
    FIX_FIRST;

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** True for the areas that name one item by {@code index}. */
    public boolean takesIndex() {
        return this == STRUCTURE_STEP || this == LANGUAGE_SUGGESTION;
    }
}
