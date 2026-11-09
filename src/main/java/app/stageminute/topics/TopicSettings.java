package app.stageminute.topics;

/**
 * The numbers of the topic catalog (root CLAUDE.md "Numbers"). {@link #LAUNCH_DEFAULT} holds the requirement values;
 * the Spring wiring fills the record from {@code platform.Settings}.
 *
 * @param rampSessions completed sessions after which the target difficulty reaches 5 (F-014: about 35)
 * @param minPerCell live topics a style and level must hold when the minimum is enforced (F-014: 20 on the server)
 */
public record TopicSettings(int rampSessions, int minPerCell) {

    public static final TopicSettings LAUNCH_DEFAULT = new TopicSettings(35, 20);

    public TopicSettings {
        if (rampSessions < 1 || minPerCell < 0) {
            throw new IllegalArgumentException(
                    "topic settings need ramp >= 1 and minimum >= 0, was " + rampSessions + " and " + minPerCell);
        }
    }
}
