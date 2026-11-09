package app.stageminute.trust.gates;

/** What a gate line says. Only {@link #PASS} counts as proof. */
public enum Status {
    PASS,
    FAIL,
    /** No number could be computed (no data, too few runs): not a pass. */
    NOT_MEASURED,
    /** A number is printed but the requirement gives no pass line yet (excellent share target, cost baseline). */
    NOT_SET
}
