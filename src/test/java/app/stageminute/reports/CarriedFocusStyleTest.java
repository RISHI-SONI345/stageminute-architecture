package app.stageminute.reports;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Which carried fixes are bound to a style (F-009, AD-140): a fix about one step keeps its take's style, a delivery
 * fix keeps none, so a new style clears only the first kind.
 */
class CarriedFocusStyleTest {

    private static ReportV2 withFix(String id, Integer step) {
        ReportV2 base = ReportFixtures.scoredWithEveryField();
        ReportV2.FixFirst fix = new ReportV2.FixFirst(id, step, "label", "how", null, null, false, false);
        return new ReportV2(
                base.v(),
                base.analysisId(),
                base.sessionId(),
                base.attempt(),
                base.style(),
                base.status(),
                base.formulaVersion(),
                base.modelVersion(),
                base.promptVersion(),
                base.schemaVersion(),
                base.score(),
                base.verdict(),
                base.delta(),
                base.facts(),
                base.findings(),
                base.quotes(),
                fix,
                base.focusCheck(),
                base.nextAttempt(),
                base.nextFocus(),
                base.carriedFocus(),
                base.strongestLine(),
                base.moments(),
                base.cautions(),
                base.trend(),
                base.noScore());
    }

    @Test
    void aStepFixKeepsTheStyleOfItsTake() {
        ReportV2 report = withFix("say_result", 3);

        assertThat(CarriedFocusService.boundStyle(report))
                .isEqualTo(report.style().wire());
    }

    @Test
    void aDeliveryFixIsBoundToNoStyle() {
        assertThat(CarriedFocusService.boundStyle(withFix("no_freeze", null))).isNull();
        assertThat(CarriedFocusService.boundStyle(withFix("steady_pace", null))).isNull();
    }
}
