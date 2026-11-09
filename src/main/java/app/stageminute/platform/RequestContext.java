package app.stageminute.platform;

import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * What the server knows about the request it is working on: a request id and, once sign-in exists, an
 * opaque user id. Both live in the logging context (MDC), so every log line carries them, and both are
 * the only identity that logs, metrics and Sentry may see (AD-23, 05 F-081).
 */
public final class RequestContext {

    public static final String HEADER = "X-Request-Id";
    public static final String REQUEST_ID_KEY = "requestId";
    public static final String USER_ID_KEY = "userId";

    /** A request id from outside is kept only when it looks like an id: no spaces, no log-breaking characters. */
    private static final Pattern ACCEPTED_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    /** An opaque user id: letters, digits, dash, underscore. An email address can never pass. */
    private static final Pattern OPAQUE_USER_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private RequestContext() {}

    /** The id of the request running on this thread, or null outside a request. */
    public static @Nullable String requestId() {
        return MDC.get(REQUEST_ID_KEY);
    }

    /** The opaque user id bound to this thread, or null. */
    public static @Nullable String userId() {
        return MDC.get(USER_ID_KEY);
    }

    /** Keeps a well-formed id sent by the caller, otherwise makes a new one. */
    static String acceptOrCreate(@Nullable String incoming) {
        if (incoming != null && ACCEPTED_REQUEST_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }

    static void bindRequestId(String requestId) {
        MDC.put(REQUEST_ID_KEY, requestId);
    }

    /**
     * Ties the signed-in user to this request's logs. Only an opaque id is accepted; anything else (an
     * email address, a name) is refused without echoing it.
     */
    public static void bindUser(String opaqueUserId) {
        if (!isOpaqueUserId(opaqueUserId)) {
            throw new IllegalArgumentException("not an opaque user id");
        }
        MDC.put(USER_ID_KEY, opaqueUserId);
    }

    public static boolean isOpaqueUserId(@Nullable String value) {
        return value != null && OPAQUE_USER_ID.matcher(value).matches();
    }

    /**
     * Runs {@code action} (a log line about the whole system, such as an alert) with no user and no request id in the
     * logging context, then puts them back. An alert is a fact about the day, not about the person whose request
     * happened to cross the line (03 F-087: "no amounts per user").
     */
    public static void withoutPerson(Runnable action) {
        String user = MDC.get(USER_ID_KEY);
        String request = MDC.get(REQUEST_ID_KEY);
        MDC.remove(USER_ID_KEY);
        MDC.remove(REQUEST_ID_KEY);
        try {
            action.run();
        } finally {
            if (user != null) {
                MDC.put(USER_ID_KEY, user);
            }
            if (request != null) {
                MDC.put(REQUEST_ID_KEY, request);
            }
        }
    }

    static void clear() {
        MDC.remove(REQUEST_ID_KEY);
        MDC.remove(USER_ID_KEY);
    }
}
