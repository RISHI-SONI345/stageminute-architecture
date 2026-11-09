package app.stageminute.analysis.vendor;

/**
 * The one AI call (03 F-029, D-84): Gemini in production (#19), {@link FakeJudge} on a development machine. It
 * returns labels and words only; code builds every number, and the pipeline drops model text that holds a number
 * ({@code LabelValidator}).
 */
public interface Judge {

    /**
     * Labels one scored take.
     *
     * @throws VendorUnavailable when the call cannot give a usable answer
     */
    JudgeLabels judge(JudgeInput input);

    /** The model, prompt and schema this judge uses; stamped on every report (AD-125). */
    JudgeVersions versions();
}
