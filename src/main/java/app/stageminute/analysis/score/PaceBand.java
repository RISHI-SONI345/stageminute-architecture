package app.stageminute.analysis.score;

/**
 * The words-a-minute range that counts as a good pace for one take (03 F-030). The caller picks it: the launch
 * default, or one built around the person's own median once they have enough takes. This class knows no user and no
 * history. Both edges count as inside.
 *
 * @param lowWpm slowest pace inside the band (85 at launch)
 * @param highWpm fastest pace inside the band (240 at launch)
 */
public record PaceBand(int lowWpm, int highWpm) {

    public static final PaceBand LAUNCH_DEFAULT = new PaceBand(85, 240);

    public PaceBand {
        if (lowWpm <= 0 || highWpm < lowWpm) {
            throw new IllegalArgumentException("a pace band needs 0 < low <= high, was " + lowWpm + ".." + highWpm);
        }
    }

    boolean contains(int wpm) {
        return wpm >= lowWpm && wpm <= highWpm;
    }
}
