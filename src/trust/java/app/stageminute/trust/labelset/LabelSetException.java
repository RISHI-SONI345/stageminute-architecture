package app.stageminute.trust.labelset;

/** The labelled set or the replay folder is not in the documented format. The message names a file, never its content. */
public final class LabelSetException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LabelSetException(String message) {
        super(message);
    }
}
