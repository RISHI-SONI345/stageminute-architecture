package app.stageminute.analysis.score;

import static app.stageminute.analysis.score.StepStatus.MISSING;
import static app.stageminute.analysis.score.StepStatus.STRONG;
import static app.stageminute.analysis.score.StepStatus.WEAK;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 03 F-030 "Done when": a fixture table of sample takes gives the expected parts. Every expected number below is
 * worked by hand from the requirement's weights, percentages and penalties (see the comment on each take), not
 * computed by the code under test.
 *
 * <p>All takes are 60 s long. {@code delivery(pauses, clusters, fillers, inBand, windows)} builds the Delivery facts.
 */
class ScoreBuilderTest {

    static final ScoreSettings SETTINGS = ScoreSettings.LAUNCH_DEFAULT;

    private static DeliveryFacts delivery(int pauses, int clusters, int fillers, int inBand, int windows) {
        List<Integer> wpm = IntStream.range(0, windows)
                .mapToObj(i -> i < inBand ? 150 : 300)
                .toList();
        return new DeliveryFacts(pauses, clusters, fillers, 60_000, wpm);
    }

    private static ScoreInput take(
            Style style, List<StepStatus> steps, int relevance, LanguageLevels language, DeliveryFacts delivery) {
        return new ScoreInput(style, steps, relevance, language, delivery, PaceBand.LAUNCH_DEFAULT);
    }

    /** input, structure, language, delivery, total, overall band, then the three part bands. */
    static Stream<Arguments> fixtureTable() {
        List<StepStatus> allStrong = List.of(STRONG, STRONG, STRONG, STRONG);
        List<StepStatus> threeStrongOneWeak = List.of(STRONG, STRONG, STRONG, WEAK);
        LanguageLevels top = new LanguageLevels(4, 4, 4);
        return Stream.of(
                // 1. Everything strong: 45 + 35 + (10 + 10) = 100
                Arguments.of(
                        "perfect take",
                        take(Style.PREP, allStrong, 4, top, delivery(0, 0, 0, 6, 6)),
                        45,
                        35,
                        20,
                        100,
                        Band.EXCELLENT,
                        Band.STRONG,
                        Band.STRONG,
                        Band.STRONG),
                // 2. The F-030 worked check. Structure 13.5 + 9 + 5.625 + 0 = 28.125 -> 28. Language (100+100+70)% of
                // 35/3 each = 31.5 -> 32. Delivery: one pause, flow 7.3, pace 10 -> 17.3 -> 17. Total 77.
                Arguments.of(
                        "F-030 worked check",
                        take(
                                Style.PREP,
                                List.of(STRONG, STRONG, WEAK, MISSING),
                                4,
                                new LanguageLevels(4, 4, 3),
                                delivery(1, 0, 0, 6, 6)),
                        28,
                        32,
                        17,
                        77,
                        Band.GOOD,
                        Band.OPPORTUNITY,
                        Band.STRONG,
                        Band.STRONG),
                // 3. Exactly 90 is Excellent. 45 + (100+70+70)% = 28 + (7.3 + 10 = 17.3 -> 17) = 90.
                Arguments.of(
                        "90 is Excellent",
                        take(Style.PREP, allStrong, 4, new LanguageLevels(4, 3, 3), delivery(1, 0, 0, 6, 6)),
                        45,
                        28,
                        17,
                        90,
                        Band.EXCELLENT,
                        Band.STRONG,
                        Band.GOOD,
                        Band.STRONG),
                // 4. 89 is Strong. Delivery: 7.3 + 9 (9 of 10 windows) = 16.3 -> 16. 45 + 28 + 16 = 89.
                Arguments.of(
                        "89 is Strong",
                        take(Style.PREP, allStrong, 4, new LanguageLevels(4, 3, 3), delivery(1, 0, 0, 9, 10)),
                        45,
                        28,
                        16,
                        89,
                        Band.STRONG,
                        Band.STRONG,
                        Band.GOOD,
                        Band.GOOD),
                // 5. Exactly 85 is Strong. Structure 13.5 + 9 + 11.25 + 5.625 = 39.375 -> 39. Language 32.
                // Delivery 7.3 + 6.667 (2 of 3) = 13.97 -> 14. 39 + 32 + 14 = 85.
                Arguments.of(
                        "85 is Strong",
                        take(Style.PREP, threeStrongOneWeak, 4, new LanguageLevels(4, 4, 3), delivery(1, 0, 0, 2, 3)),
                        39,
                        32,
                        14,
                        85,
                        Band.STRONG,
                        Band.STRONG,
                        Band.STRONG,
                        Band.GOOD),
                // 6. 84 is Good. Delivery 7.3 + 6 (6 of 10) = 13.3 -> 13, which is 65% of 20: Opportunity. 39 + 32 + 13
                // = 84.
                Arguments.of(
                        "84 is Good",
                        take(Style.PREP, threeStrongOneWeak, 4, new LanguageLevels(4, 4, 3), delivery(1, 0, 0, 6, 10)),
                        39,
                        32,
                        13,
                        84,
                        Band.GOOD,
                        Band.STRONG,
                        Band.STRONG,
                        Band.OPPORTUNITY),
                // 7. Exactly 70 is Good, and Delivery 14 of 20 is exactly 70%. Structure 28. Language (100+100+40)% =
                // 28. Delivery 10 + 4 (2 of 5) = 14. 28 + 28 + 14 = 70.
                Arguments.of(
                        "70 is Good",
                        take(
                                Style.PREP,
                                List.of(STRONG, STRONG, WEAK, MISSING),
                                4,
                                new LanguageLevels(4, 4, 2),
                                delivery(0, 0, 0, 2, 5)),
                        28,
                        28,
                        14,
                        70,
                        Band.GOOD,
                        Band.OPPORTUNITY,
                        Band.GOOD,
                        Band.GOOD),
                // 8. 69 is Opportunity. Delivery 10 + 3 (3 of 10) = 13. 28 + 28 + 13 = 69.
                Arguments.of(
                        "69 is Opportunity",
                        take(
                                Style.PREP,
                                List.of(STRONG, STRONG, WEAK, MISSING),
                                4,
                                new LanguageLevels(4, 4, 2),
                                delivery(0, 0, 0, 3, 10)),
                        28,
                        28,
                        13,
                        69,
                        Band.OPPORTUNITY,
                        Band.OPPORTUNITY,
                        Band.GOOD,
                        Band.OPPORTUNITY),
                // 9. Relevance level 1 caps Structure at 22 even with every step strong. Language 40% x 3 = 14.
                // Delivery 10 + 0 = 10. 22 + 14 + 10 = 46.
                Arguments.of(
                        "relevance 1 caps Structure",
                        take(Style.PREP, allStrong, 1, new LanguageLevels(2, 2, 2), delivery(0, 0, 0, 0, 6)),
                        22,
                        14,
                        10,
                        46,
                        Band.OPPORTUNITY,
                        Band.OPPORTUNITY,
                        Band.OPPORTUNITY,
                        Band.OPPORTUNITY),
                // 10. The worst take. Structure 0. Language 10% x 3 = 3.5 -> 4. Delivery: four pauses floor flow at 0,
                // no windows in band. 0 + 4 + 0 = 4.
                Arguments.of(
                        "worst take",
                        take(
                                Style.PREP,
                                List.of(MISSING, MISSING, MISSING, MISSING),
                                1,
                                new LanguageLevels(1, 1, 1),
                                delivery(4, 0, 0, 0, 6)),
                        0,
                        4,
                        0,
                        4,
                        Band.OPPORTUNITY,
                        Band.OPPORTUNITY,
                        Band.OPPORTUNITY,
                        Band.OPPORTUNITY),
                // 11. A STAR take. Structure 4.5 + 6.75 + 15.75 + 0 = 27. Language 35. Delivery 20. Total 82.
                Arguments.of(
                        "STAR take",
                        take(Style.STAR, List.of(WEAK, STRONG, STRONG, MISSING), 4, top, delivery(0, 0, 0, 6, 6)),
                        27,
                        35,
                        20,
                        82,
                        Band.GOOD,
                        Band.OPPORTUNITY,
                        Band.STRONG,
                        Band.STRONG));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureTable")
    void theFixtureTableGivesTheExpectedParts(
            String name,
            ScoreInput input,
            int structure,
            int language,
            int deliveryPoints,
            int total,
            Band overall,
            Band structureBand,
            Band languageBand,
            Band deliveryBand) {
        ScoreResult result = ScoreBuilder.build(input, SETTINGS);

        assertThat(result.parts())
                .extracting(PartScore::id, PartScore::points, PartScore::max, PartScore::band)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("structure", structure, 45, structureBand),
                        org.assertj.core.api.Assertions.tuple("language", language, 35, languageBand),
                        org.assertj.core.api.Assertions.tuple("delivery", deliveryPoints, 20, deliveryBand));
        assertThat(result.total()).isEqualTo(total);
        assertThat(result.overallBand()).isEqualTo(overall);
        assertThat(result.formulaVersion()).isEqualTo("2026-10-17");
    }

    @Test
    void theTotalIsTheSumOfTheRoundedPartsNotTheRoundedSumOfTheExactParts() {
        // Structure 22.5 -> 23, Language 24.5 -> 25, Delivery 7.3 + 5.2 = 12.5 -> 13. The exact total is 59.5, but
        // F-030
        // says each part is rounded first and the total is their sum: 61.
        ScoreInput input = take(
                Style.PREP, List.of(WEAK, WEAK, WEAK, WEAK), 4, new LanguageLevels(3, 3, 3), delivery(1, 0, 0, 13, 25));

        ScoreResult result = ScoreBuilder.build(input, SETTINGS);

        assertThat(result.parts()).extracting(PartScore::points).containsExactly(23, 25, 13);
        assertThat(result.total()).isEqualTo(61);
    }

    @Test
    void aScoreInputNeedsEveryPart() {
        List<StepStatus> steps = List.of(STRONG, STRONG, STRONG, STRONG);
        LanguageLevels language = new LanguageLevels(4, 4, 4);
        DeliveryFacts facts = delivery(0, 0, 0, 6, 6);
        PaceBand band = PaceBand.LAUNCH_DEFAULT;

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ScoreInput(null, steps, 4, language, facts, band))
                .isInstanceOf(NullPointerException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ScoreInput(Style.PREP, null, 4, language, facts, band))
                .isInstanceOf(NullPointerException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ScoreInput(Style.PREP, steps, 4, null, facts, band))
                .isInstanceOf(NullPointerException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ScoreInput(Style.PREP, steps, 4, language, null, band))
                .isInstanceOf(NullPointerException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ScoreInput(Style.PREP, steps, 4, language, facts, null))
                .isInstanceOf(NullPointerException.class);
    }
}
