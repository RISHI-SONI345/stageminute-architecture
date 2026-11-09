package app.stageminute.practice;

/**
 * The attempt caps (03 F-047: "the cap is a server constant"; D-15). {@link #LAUNCH_DEFAULT} holds the requirement
 * values.
 *
 * @param baseAttempts attempts a topic always allows (2)
 * @param maxAttempts attempts at most, the last one only after Better or Not yet (3)
 */
public record AttemptSettings(int baseAttempts, int maxAttempts) {

    public static final AttemptSettings LAUNCH_DEFAULT = new AttemptSettings(2, 3);

    public AttemptSettings {
        if (baseAttempts < 1 || maxAttempts < baseAttempts) {
            throw new IllegalArgumentException(
                    "attempt caps need 1 <= base <= max, was " + baseAttempts + " and " + maxAttempts);
        }
    }
}
