package app.stageminute.analysis.score;

/** How a filler candidate is treated (03 F-031, requirements version 1.7). */
public enum FillerKind {
    /** um, uh, er, erm, ah, hmm: code confirms them, the AI call is not asked. */
    ALWAYS_COUNTED,
    /** like, so, you know ...: the one AI call labels each as filler, meaningful or hedge. */
    IN_CONTEXT,
    /** kind of, sort of, I think, maybe: counted on Delivery, never scored and never a Fix at launch. */
    HEDGE
}
