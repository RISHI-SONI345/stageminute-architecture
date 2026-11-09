package app.stageminute.analysis.score;

/**
 * The gate's decision.
 *
 * @param status why the take is, or is not, scored
 * @param shortCaution true when the take is scored but is short (40 or more words under 30 s)
 */
public record GateResult(TakeStatus status, boolean shortCaution) {

    public boolean scored() {
        return status.scored();
    }
}
