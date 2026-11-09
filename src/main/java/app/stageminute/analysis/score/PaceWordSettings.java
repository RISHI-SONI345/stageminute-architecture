package app.stageminute.analysis.score;

/**
 * The limits of the pace word (C-65). The pipeline reads them by name from the server settings
 * ({@code pace_word_steady_in_band_percent}, {@code pace_word_one_side_percent}); {@link #LAUNCH_DEFAULT} holds the
 * values chosen for launch (AD-126).
 *
 * @param steadyInBandPercent the take is steady when at least this share of its pace windows is inside the band (80,
 *     the same share that finishes the {@code steady_pace} fix in F-032)
 * @param oneSidePercent outside the band, a bit fast or a bit slow when at least this share of the outside windows is
 *     on one side (75); above 50, so only one side can win
 */
public record PaceWordSettings(int steadyInBandPercent, int oneSidePercent) {

    public static final PaceWordSettings LAUNCH_DEFAULT = new PaceWordSettings(80, 75);

    private static final int HALF = 50;
    private static final int ALL = 100;

    public PaceWordSettings {
        if (steadyInBandPercent <= 0 || steadyInBandPercent > ALL) {
            throw new IllegalArgumentException("steadyInBandPercent is 1..100, was " + steadyInBandPercent);
        }
        if (oneSidePercent <= HALF || oneSidePercent > ALL) {
            throw new IllegalArgumentException("oneSidePercent is 51..100, was " + oneSidePercent);
        }
    }
}
