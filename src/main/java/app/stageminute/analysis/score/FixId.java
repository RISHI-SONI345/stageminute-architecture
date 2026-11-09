package app.stageminute.analysis.score;

/**
 * The fixes in the Fix Library (03 F-032), in the order the code checks them. The text of each fix (label, how, try)
 * is copy and comes from {@code content/}; here the code owns only the choice.
 */
public enum FixId {
    SPEAK_LONGER("speak_longer"),
    ANSWER_QUESTION("answer_question"),
    OPEN_POINT("open_point"),
    SET_SCENE("set_scene"),
    ADD_CLOSING("add_closing"),
    SAY_RESULT("say_result"),
    ADD_EXAMPLE("add_example"),
    NAME_ACTION("name_action"),
    ADD_REASON("add_reason"),
    NAME_TASK("name_task"),
    NO_FREEZE("no_freeze"),
    FILLER_CLUSTER("filler_cluster"),
    START_SOONER("start_sooner"),
    STRENGTHEN_STEP("strengthen_step"),
    SPLIT_SENTENCE("split_sentence"),
    STEADY_PACE("steady_pace"),
    SAY_IT_PLAINLY("say_it_plainly"),
    SAY_IT_SHORTER("say_it_shorter"),
    MORE_STANDARD("more_standard"),
    WRAP_UP_IN_TIME("wrap_up_in_time");

    private final String code;

    FixId(String code) {
        this.code = code;
    }

    /** The id as the report and the content files write it. */
    public String code() {
        return code;
    }

    /** Whether the fix is about one step of the answer, so a {@link FixChoice} of it must say which step. */
    boolean aboutOneStep() {
        return switch (this) {
            case OPEN_POINT,
                    SET_SCENE,
                    ADD_CLOSING,
                    SAY_RESULT,
                    ADD_EXAMPLE,
                    NAME_ACTION,
                    ADD_REASON,
                    NAME_TASK,
                    STRENGTHEN_STEP,
                    WRAP_UP_IN_TIME -> true;
            default -> false;
        };
    }

    /**
     * The fix for a missing step. Step 0 is Point or Situation, 1 Reason or Task, 2 Example or Action, 3 Closing or
     * Result (the style's order, as in {@link ScoreInput#steps()}).
     */
    static FixId forMissingStep(Style style, int stepIndex) {
        boolean prep = style == Style.PREP;
        return switch (stepIndex) {
            case 0 -> prep ? OPEN_POINT : SET_SCENE;
            case 1 -> prep ? ADD_REASON : NAME_TASK;
            case 2 -> prep ? ADD_EXAMPLE : NAME_ACTION;
            case 3 -> prep ? ADD_CLOSING : SAY_RESULT;
            default -> throw new IllegalArgumentException("a step is 0 to 3, was " + stepIndex);
        };
    }
}
