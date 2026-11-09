package app.stageminute.analysis.score;

/**
 * Every weight and cut-off of the score formula in one place (03 F-030: "all weights and cut-offs are server
 * config"). {@link #LAUNCH_DEFAULT} holds the values approved in D-55 and D-70 and is used until settings override
 * them. A changed weight needs a new {@code formulaVersion} and a row in 01 (D-55).
 *
 * <p>The constructor checks that the parts agree with each other, so a bad settings row fails at start-up and never
 * produces a wrong score: both styles are worth the same Structure maximum, every part is a whole number of points,
 * and the three parts add up to 100.
 *
 * @param formulaVersion stored on every report
 * @param structure Structure weights, factors and the relevance cap (45 points)
 * @param language Language level shares (35 points)
 * @param delivery Delivery flow and pace (20 points)
 * @param cutoffs band cut-offs
 */
public record ScoreSettings(
        String formulaVersion,
        StructureSettings structure,
        LanguageSettings language,
        DeliverySettings delivery,
        BandCutoffs cutoffs) {

    /** The three parts add up to the 0..100 total. */
    private static final int TOTAL = 100;

    private static final int CP_PER_POINT = 100;

    public static final ScoreSettings LAUNCH_DEFAULT = new ScoreSettings(
            "2026-10-17",
            StructureSettings.LAUNCH_DEFAULT,
            LanguageSettings.LAUNCH_DEFAULT,
            DeliverySettings.LAUNCH_DEFAULT,
            BandCutoffs.LAUNCH_DEFAULT);

    public ScoreSettings {
        if (formulaVersion == null || formulaVersion.isBlank()) {
            throw new IllegalArgumentException("formulaVersion is required");
        }
        if (structure.prep().totalCp() != structure.star().totalCp()) {
            throw new IllegalArgumentException("both styles must be worth the same Structure points");
        }
        // Read the parameters, not the accessors: a compact constructor has not assigned the fields yet.
        int structureCp = structure.prep().totalCp();
        int deliveryCp = delivery.flowMaxCp() + delivery.paceMaxCp();
        if (structureCp % CP_PER_POINT != 0 || deliveryCp % CP_PER_POINT != 0) {
            throw new IllegalArgumentException("every part must be a whole number of points");
        }
        if (structureCp / CP_PER_POINT + language.maxPoints() + deliveryCp / CP_PER_POINT != TOTAL) {
            throw new IllegalArgumentException("the parts must add up to " + TOTAL);
        }
    }

    public int structureMax() {
        return structure.prep().totalCp() / CP_PER_POINT;
    }

    public int languageMax() {
        return language.maxPoints();
    }

    public int deliveryMax() {
        return (delivery.flowMaxCp() + delivery.paceMaxCp()) / CP_PER_POINT;
    }
}
