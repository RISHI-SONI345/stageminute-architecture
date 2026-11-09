package app.stageminute.trust.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class KappaTest {

    /** Rows are labeller A, columns labeller B; builds two lists from a count table. */
    private static List<List<String>> fromTable(int yesYes, int yesNo, int noYes, int noNo) {
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        add(a, b, "yes", "yes", yesYes);
        add(a, b, "yes", "no", yesNo);
        add(a, b, "no", "yes", noYes);
        add(a, b, "no", "no", noNo);
        return List.of(a, b);
    }

    private static void add(List<String> a, List<String> b, String x, String y, int times) {
        for (int i = 0; i < times; i++) {
            a.add(x);
            b.add(y);
        }
    }

    @Test
    void eightyFivePercentRawAgreementIsOnlyAboutHalfAfterChance() {
        // Hand check: observed 0.85. A says yes 80 of 100, B says yes 75 of 100, so chance agreement is
        // 0.80 x 0.75 + 0.20 x 0.25 = 0.65. Kappa = (0.85 - 0.65) / (1 - 0.65) = 0.5714.
        List<List<String>> pair = fromTable(70, 10, 5, 15);

        assertThat(Kappa.rawAgreement(pair.get(0), pair.get(1))).isCloseTo(0.85, within(1e-9));
        assertThat(Kappa.cohen(pair.get(0), pair.get(1))).isCloseTo(0.5714, within(1e-4));
    }

    @Test
    void perfectAgreementIsOne() {
        List<String> labels = List.of("strong", "weak", "missing", "strong");

        assertThat(Kappa.cohen(labels, labels)).isEqualTo(1.0);
    }

    @Test
    void agreementThatChanceAloneWouldGiveIsZero() {
        // A is always yes, B is yes half the time: observed 0.5, chance 0.5 x 1.0 = 0.5.
        List<String> a = List.of("yes", "yes", "yes", "yes");
        List<String> b = List.of("yes", "no", "yes", "no");

        assertThat(Kappa.cohen(a, b)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void everyoneUsingTheSameSingleLabelCountsAsFullAgreement() {
        assertThat(Kappa.cohen(List.of("weak", "weak"), List.of("weak", "weak")))
                .isEqualTo(1.0);
    }

    @Test
    void worseThanChanceIsNegative() {
        assertThat(Kappa.cohen(List.of("yes", "no"), List.of("no", "yes"))).isLessThan(0.0);
    }

    @Test
    void listsOfDifferentLengthOrEmptyListsAreRefused() {
        assertThatThrownBy(() -> Kappa.cohen(List.of("a"), List.of("a", "b")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Kappa.cohen(List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Kappa.rawAgreement(List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
