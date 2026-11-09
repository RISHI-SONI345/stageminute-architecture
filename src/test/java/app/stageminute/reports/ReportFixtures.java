package app.stageminute.reports;

import app.stageminute.reports.ReportV2.BandSource;
import app.stageminute.reports.ReportV2.BandWire;
import app.stageminute.reports.ReportV2.Caution;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Two complete Report v2 values for contract tests: a scored take with every field filled (except {@code trend}, which
 * is always null at launch, and {@code no_score}), and a take with no score. Numbers only where the shape allows them.
 */
public final class ReportFixtures {

    public static final UUID ANALYSIS = UUID.fromString("0190f0a0-0000-7000-8000-000000000001");
    public static final UUID SESSION = UUID.fromString("0190f0a0-0000-7000-8000-000000000002");

    private ReportFixtures() {}

    public static ReportV2 scoredWithEveryField() {
        return new ReportV2(
                ReportV2.VERSION,
                ANALYSIS,
                SESSION,
                2,
                ReportV2.StyleWire.PREP,
                ReportV2.Status.OK,
                "2026-10-17",
                "fake-judge",
                "fake-prompt-0",
                "fake-schema-0",
                new ReportV2.Score(
                        86,
                        BandWire.STRONG,
                        List.of(
                                new ReportV2.Part(ReportV2.PartId.STRUCTURE, 41, 45, BandWire.STRONG),
                                new ReportV2.Part(ReportV2.PartId.LANGUAGE, 29, 35, BandWire.GOOD),
                                new ReportV2.Part(ReportV2.PartId.DELIVERY, 16, 20, BandWire.GOOD))),
                "You closed this time.",
                new ReportV2.Delta(1, 78, ReportV2.Direction.UP),
                new ReportV2.Facts(
                        55_000,
                        131,
                        new ReportV2.Pace(
                                143,
                                151,
                                ReportV2.PaceWordWire.STEADY,
                                false,
                                List.of(85, 240),
                                BandSource.DEFAULT,
                                List.of(148, 172, 136, 124, 138)),
                        List.of(new ReportV2.Pause(31_000, 6600, true)),
                        2,
                        6600,
                        900,
                        new ReportV2.Fillers(
                                4,
                                2,
                                4,
                                List.of(new ReportV2.FillerItem(3, 6200)),
                                List.of(new ReportV2.Cluster(6000, 14_000, 3))),
                        1,
                        21,
                        List.of(new ReportV2.Misheard(41)),
                        0.03,
                        false),
                new ReportV2.Findings(
                        List.of(new ReportV2.Step(ReportV2.StepName.POINT, ReportV2.StepStatusWire.STRONG, 1)),
                        4,
                        new ReportV2.Rubric(4, 3, 3, 4),
                        List.of(new ReportV2.LanguageItem(0, ReportV2.LanguageKind.GRAMMAR, 2, 14, 15)),
                        List.of(new ReportV2.LanguageItem(1, ReportV2.LanguageKind.SWAP, 4, 30, 30)),
                        false,
                        0),
                Map.of("1", "The most useful thing I did this year was fixing our slow checkout."),
                new ReportV2.FixFirst("no_freeze", 3, "label", "how", "try line", "m2", false, false),
                new ReportV2.FocusCheck(
                        "add_closing",
                        ReportV2.FocusResultWire.DONE,
                        new ReportV2.Evidence(ReportV2.MeasureWire.STEP_STATUS, 0, 2),
                        ReportV2.StepStatusWire.MISSING),
                new ReportV2.NextAttempt(false, "focus_done"),
                new ReportV2.NextFocus("no_freeze"),
                new ReportV2.CarriedFocus(
                        "add_closing", "Add a closing.", UUID.fromString("0190f0a0-0000-7000-8000-0000000000c1")),
                new ReportV2.StrongestLine(5, "checkout got about twice as fast.", 36_000, 39_000, "why"),
                List.of(new ReportV2.Moment("m1", ReportV2.MomentKindWire.PAUSE, 31_000, 37_600)),
                List.of(Caution.LOW_AUDIO),
                null,
                null);
    }

    public static ReportV2 noScore() {
        return new ReportV2(
                ReportV2.VERSION,
                ANALYSIS,
                SESSION,
                1,
                ReportV2.StyleWire.STAR,
                ReportV2.Status.SHORT_TAKE,
                "2026-10-17",
                "fake-judge",
                "fake-prompt-0",
                "fake-schema-0",
                null,
                null,
                null,
                new ReportV2.Facts(
                        24_000,
                        31,
                        new ReportV2.Pace(98, 104, null, false, List.of(85, 240), BandSource.DEFAULT, List.of(96, 102)),
                        List.of(),
                        0,
                        0,
                        1400,
                        new ReportV2.Fillers(0, 0, 0, List.of(), List.of()),
                        0,
                        12,
                        List.of(),
                        0.0,
                        false),
                null,
                Map.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                new ReportV2.NoScore(ReportV2.Status.SHORT_TAKE, 31, 24_000, false));
    }
}
