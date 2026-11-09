package app.stageminute.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.reports.Patterns;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PatternsCalculatorTest {

    private static final Patterns.FixedOnSecond NONE = new Patterns.FixedOnSecond(0, List.of());

    private static List<String> take(String... labels) {
        return List.of(labels);
    }

    private static Patterns prep(List<List<String>> takes) {
        return PatternsCalculator.compute("prep", takes, 5, 4, 3, NONE);
    }

    @Test
    void fewerThanFiveTakesShowOnlyTheCount() {
        Patterns patterns = prep(List.of(take("strong", "strong", "strong", "strong")));

        assertThat(patterns.takesCounted()).isEqualTo(1);
        assertThat(patterns.needed()).isEqualTo(5);
        assertThat(patterns.strength()).isNull();
        assertThat(patterns.opportunity()).isNull();
        assertThat(patterns.allStrong()).isFalse();
    }

    @Test
    void theStepStrongInFourOfFiveIsTheStrengthAndAStepWeakInThreeIsTheOpportunity() {
        Patterns patterns = prep(List.of(
                take("strong", "weak", "strong", "missing"),
                take("strong", "weak", "strong", "missing"),
                take("strong", "weak", "weak", "strong"),
                take("strong", "strong", "strong", "weak"),
                take("weak", "strong", "strong", "missing")));

        assertThat(patterns.strength()).isEqualTo(new Patterns.Strength("point", 4));
        assertThat(patterns.opportunity()).isEqualTo(new Patterns.Opportunity("closing", 4));
        assertThat(patterns.allStrong()).isFalse();
    }

    @Test
    void twoWeakOfFiveIsNotAnOpportunityAndMeansAllStrong() {
        Patterns patterns = prep(List.of(
                take("strong", "strong", "strong", "strong"),
                take("strong", "strong", "strong", "strong"),
                take("strong", "strong", "strong", "weak"),
                take("strong", "strong", "strong", "weak"),
                take("strong", "strong", "strong", "strong")));

        assertThat(patterns.opportunity()).isNull();
        assertThat(patterns.allStrong()).isTrue();
        assertThat(patterns.strength()).isEqualTo(new Patterns.Strength("point", 5));
    }

    @Test
    void starUsesItsOwnStepCodesAndOnlyTheNewestFiveAreCounted() {
        List<List<String>> takes = IntStream.range(0, 7)
                .mapToObj(i -> i < 5
                        ? take("weak", "strong", "strong", "strong")
                        : take("strong", "strong", "strong", "strong"))
                .collect(Collectors.toList());

        Patterns patterns = PatternsCalculator.compute("star", takes, 5, 4, 3, NONE);

        assertThat(patterns.takesCounted()).isEqualTo(5);
        assertThat(patterns.opportunity()).isEqualTo(new Patterns.Opportunity("situation", 5));
        assertThat(patterns.strength().criterion()).isEqualTo("task");
    }

    @Test
    void theSecondAttemptPartIsPassedThrough() {
        Patterns.FixedOnSecond fixed = new Patterns.FixedOnSecond(1, List.of(LocalDate.parse("2026-10-14")));

        assertThat(PatternsCalculator.compute("prep", List.of(), 5, 4, 3, fixed).fixedOnSecond())
                .isEqualTo(fixed);
    }
}
