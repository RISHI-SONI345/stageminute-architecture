package app.stageminute.analysis.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 03 F-030 / D-55: Structure 45 + Language 35 + Delivery 20, under a formula_version. */
class ScoreSettingsTest {

    static final StructureSettings STRUCTURE = StructureSettings.LAUNCH_DEFAULT;
    static final LanguageSettings LANGUAGE = LanguageSettings.LAUNCH_DEFAULT;
    static final DeliverySettings DELIVERY = DeliverySettings.LAUNCH_DEFAULT;
    static final BandCutoffs CUTOFFS = BandCutoffs.LAUNCH_DEFAULT;

    @Test
    void launchDefaultIs45And35And20UnderTheApprovedVersion() {
        ScoreSettings s = ScoreSettings.LAUNCH_DEFAULT;

        assertThat(s.structureMax()).isEqualTo(45);
        assertThat(s.languageMax()).isEqualTo(35);
        assertThat(s.deliveryMax()).isEqualTo(20);
        assertThat(s.formulaVersion()).isEqualTo("2026-10-17");
        assertThat(s.cutoffs()).isEqualTo(BandCutoffs.LAUNCH_DEFAULT);
    }

    @Test
    void thePartsMustAddUpToOneHundred() {
        // Delivery worth 21 points: 45 + 35 + 21 = 101
        DeliverySettings tooBig = new DeliverySettings(1000, 1100, 270, 200, 130, 8);

        assertThatThrownBy(() -> new ScoreSettings("v", STRUCTURE, LANGUAGE, tooBig, CUTOFFS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
    }

    @Test
    void bothStylesMustBeWorthTheSameStructureMax() {
        // STAR adds up to 44.5, PREP to 45
        StructureSettings uneven =
                new StructureSettings(STRUCTURE.prep(), new StepWeights(900, 675, 1575, 1300), 100, 50, 22);

        assertThatThrownBy(() -> new ScoreSettings("v", uneven, LANGUAGE, DELIVERY, CUTOFFS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("style");
    }

    @Test
    void everyPartMustBeAWholeNumberOfPoints() {
        // Delivery of 20.5 points
        DeliverySettings half = new DeliverySettings(1000, 1050, 270, 200, 130, 8);

        assertThatThrownBy(() -> new ScoreSettings("v", STRUCTURE, LANGUAGE, half, CUTOFFS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole");
    }

    @Test
    void everyReportNeedsAFormulaVersion() {
        assertThatThrownBy(() -> new ScoreSettings(" ", STRUCTURE, LANGUAGE, DELIVERY, CUTOFFS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScoreSettings(null, STRUCTURE, LANGUAGE, DELIVERY, CUTOFFS))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
