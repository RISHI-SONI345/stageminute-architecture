package app.stageminute.platform;

import java.util.Map;

/**
 * A planned "no" with a reason code (06-API section 1). {@link ProblemAdvice} turns it into an RFC 9457
 * problem. The message is only the code, so nothing the user sent can reach a log line through it.
 */
public final class BusinessRefusal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Reason reason;
    private final transient Map<String, Object> extra;

    public BusinessRefusal(Reason reason) {
        this(reason, Map.of());
    }

    /** {@code extra} holds the fields the reason table names, for example {@code resets_at}. */
    public BusinessRefusal(Reason reason, Map<String, Object> extra) {
        super(reason.code(), null, false, false);
        this.reason = reason;
        this.extra = Map.copyOf(extra);
    }

    public Reason reason() {
        return reason;
    }

    public Map<String, Object> extra() {
        return extra;
    }
}
