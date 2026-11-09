package app.stageminute.analysis.score;

/**
 * Band cut-offs. They are server settings (03 F-030: "all weights and cut-offs are server config");
 * {@link #LAUNCH_DEFAULT} holds the values decided in D-13 and is used until settings override it.
 *
 * @param overallExcellent lowest total that reads Excellent (90)
 * @param overallStrong    lowest total that reads Strong (85)
 * @param overallGood      lowest total that reads Good (70)
 * @param partStrongPct    lowest share of a part's points that reads Strong (85)
 * @param partGoodPct      lowest share of a part's points that reads Good (70)
 */
public record BandCutoffs(
        int overallExcellent, int overallStrong, int overallGood, int partStrongPct, int partGoodPct) {

    public static final BandCutoffs LAUNCH_DEFAULT = new BandCutoffs(90, 85, 70, 85, 70);

    public BandCutoffs {
        if (!(overallExcellent > overallStrong && overallStrong > overallGood && overallGood > 0)) {
            throw new IllegalArgumentException("overall cut-offs must fall: excellent > strong > good > 0");
        }
        if (!(partStrongPct > partGoodPct && partGoodPct > 0 && partStrongPct <= 100)) {
            throw new IllegalArgumentException("part cut-offs must fall: 100 >= strong > good > 0");
        }
    }
}
