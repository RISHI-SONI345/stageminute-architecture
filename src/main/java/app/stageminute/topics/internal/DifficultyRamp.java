package app.stageminute.topics.internal;

/** The difficulty a user should meet next: 1 for a new user, rising in four equal steps to 5 (F-014). */
public final class DifficultyRamp {

    private static final int EASIEST = 1;
    private static final int HARDEST = 5;

    private DifficultyRamp() {}

    /**
     * {@code 1 + floor(4 x completed / rampSessions)}, never above 5. With the launch value of 35 sessions the target
     * is 2 from the 9th session, 3 from the 18th, 4 from the 27th and 5 from the 35th.
     */
    public static int target(int completedSessions, int rampSessions) {
        long steps = HARDEST - EASIEST;
        // A setting of 0 would divide by zero; it means "no ramp", so the hardest topics from the start.
        long ramp = Math.max(1, rampSessions);
        long reached = Math.min(completedSessions, ramp);
        return (int) (EASIEST + steps * reached / ramp);
    }
}
