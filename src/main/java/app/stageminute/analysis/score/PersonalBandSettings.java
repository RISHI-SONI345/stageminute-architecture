package app.stageminute.analysis.score;

/**
 * Settings of the personal pace band (03 F-027, requirements version 1.7). {@link #LAUNCH_DEFAULT} holds the
 * requirement values.
 *
 * @param minTakes past scored takes needed before the band is personal (5)
 * @param lastTakes how many of the latest past takes the median is taken from (10)
 * @param halfWidthWpm words a minute below and above the median (25)
 * @param limits the band is never wider than this (85 to 240)
 */
public record PersonalBandSettings(int minTakes, int lastTakes, int halfWidthWpm, PaceBand limits) {

    public static final PersonalBandSettings LAUNCH_DEFAULT =
            new PersonalBandSettings(5, 10, 25, PaceBand.LAUNCH_DEFAULT);

    public PersonalBandSettings {
        if (minTakes <= 0 || lastTakes < minTakes || halfWidthWpm <= 0) {
            throw new IllegalArgumentException("needs 0 < minTakes <= lastTakes and a positive half width");
        }
        if (limits == null) {
            throw new IllegalArgumentException("limits are required");
        }
    }
}
