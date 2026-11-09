package app.stageminute.trust.gates;

/**
 * One line of the gate table: the measured number NEXT TO the pass line, with its range and its source.
 *
 * @param id a stable key, used in the verdict line and in saved runs
 * @param name the gate in plain words
 * @param measured the measured number as text
 * @param range the 95 % bootstrap range as text, or "no range"
 * @param passLine the pass line as text
 * @param status pass, fail, not measured or not set
 * @param source where the pass line comes from
 */
public record Gate(
        String id, String name, String measured, String range, String passLine, Status status, String source) {}
