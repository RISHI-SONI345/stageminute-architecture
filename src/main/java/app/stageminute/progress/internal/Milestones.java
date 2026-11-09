package app.stageminute.progress.internal;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Which milestone to celebrate (03 F-060, F-120). Pure. Lengths are days, never sessions.
 *
 * <p>A milestone is pending when the streak has reached it, it was never marked seen, and the streak is the best the
 * person has had (so a streak that broke and climbs back to 7 does not celebrate again: 7 is already seen, and a
 * lower streak than the best never celebrates). Marking one seen also marks every smaller one, so a person who skipped
 * the app past 3 and 7 sees one celebration, not three.
 */
public final class Milestones {

    static final String PREFIX = "milestone_";

    private Milestones() {}

    public static @Nullable Integer pending(int current, int best, List<Integer> milestones, Set<Integer> seen) {
        if (current <= 0 || current < best) {
            return null;
        }
        return milestones.stream()
                .filter(days -> days <= current && !seen.contains(days))
                .max(Integer::compare)
                .orElse(null);
    }

    /** The marker word of a milestone in {@code seen_markers}, for example {@code milestone_7}. */
    public static String marker(int days) {
        return PREFIX + days;
    }

    /** The milestone a marker word names, or null when it is not one. */
    public static @Nullable Integer daysOf(String marker) {
        if (!marker.startsWith(PREFIX)) {
            return null;
        }
        String number = marker.substring(PREFIX.length());
        if (number.isEmpty() || number.length() > 9 || !number.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Integer.parseInt(number);
    }

    /** The markers to write when {@code days} is seen: it and every smaller milestone. */
    public static List<String> markersUpTo(int days, List<Integer> milestones) {
        return Stream.concat(Stream.of(days), milestones.stream().filter(m -> m < days))
                .distinct()
                .map(Milestones::marker)
                .toList();
    }
}
