package app.stageminute.analysis.score;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds numbers in the model's text (03 F-029, root rule 5: code builds every number, the model writes labels and
 * words only). The pipeline treats a hit as invalid model output. Pure: no clock, no user, no Spring.
 *
 * <p>A number is any digit in any script, a fraction or circled digit, a percent sign, or a spelled-out number from
 * "zero" and "two" up to "billion", and "percent". Words are matched whole, so "often" and "someone" pass. "One" and
 * ordinals such as "first" stay allowed: "give one example" and "say the point first" are ordinary coaching words
 * (decision logged in step3/CONFLICTS.md C-30).
 */
public final class LabelValidator {

    private static final Set<String> NUMBER_WORDS = Set.of(
            "zero",
            "two",
            "three",
            "four",
            "five",
            "six",
            "seven",
            "eight",
            "nine",
            "ten",
            "eleven",
            "twelve",
            "thirteen",
            "fourteen",
            "fifteen",
            "sixteen",
            "seventeen",
            "eighteen",
            "nineteen",
            "twenty",
            "thirty",
            "forty",
            "fifty",
            "sixty",
            "seventy",
            "eighty",
            "ninety",
            "hundred",
            "thousand",
            "million",
            "billion",
            "percent");

    private static final Pattern INVISIBLE = Pattern.compile("\\p{Cf}");

    private LabelValidator() {}

    /** True when the text holds a number in the sense above. Null or empty text has none. */
    public static boolean hasNumber(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (isNumericCharacter(codePoint)) {
                return true;
            }
            i += Character.charCount(codePoint);
        }
        // Invisible format characters (zero-width space, soft hyphen) would split "two" into "tw" and "o".
        String visible = INVISIBLE.matcher(text).replaceAll("");
        for (String token : visible.toLowerCase(Locale.ROOT).split("[^\\p{L}]+", -1)) {
            if (NUMBER_WORDS.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNumericCharacter(int codePoint) {
        if (codePoint == '%') {
            return true;
        }
        int type = Character.getType(codePoint);
        return type == Character.DECIMAL_DIGIT_NUMBER
                || type == Character.LETTER_NUMBER
                || type == Character.OTHER_NUMBER;
    }
}
