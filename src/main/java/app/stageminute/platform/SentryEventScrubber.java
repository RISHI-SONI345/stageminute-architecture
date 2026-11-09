package app.stageminute.platform;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Contexts;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.User;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Runs on every event just before it leaves the server (AD-23, 05 F-081). It keeps what helps to find a
 * bug (error type, stack, the request id, an opaque user id) and drops everything that could hold a
 * person's data: the request (body, cookies, headers, query, address), user fields other than the
 * opaque id, free text in messages and exception values, extras and breadcrumbs. Fail closed: a new
 * field Sentry adds later is not copied by this code, and the SDK is also told not to send default
 * personal data.
 */
@Component
class SentryEventScrubber implements SentryOptions.BeforeSendCallback {

    private static final Set<String> KEPT_CONTEXTS = Set.of("os", "runtime");

    @Override
    public @Nullable SentryEvent execute(SentryEvent event, Hint hint) {
        // ProblemAdvice logs every unplanned error for the log trail; ProblemAdvice reports the error itself.
        if (ProblemAdvice.class.getName().equals(event.getLogger())) {
            return null;
        }
        event.setRequest(null);
        event.setBreadcrumbs(null);
        event.setExtras(null);
        event.setTransaction(null);
        event.setThreads(null);
        event.setFingerprints(null);
        event.setTags(new java.util.HashMap<>());
        // Contexts keep what describes the runtime; the rest (the log appender copies MDC into them) goes.
        Contexts contexts = event.getContexts();
        for (String key : Collections.list(contexts.keys())) {
            if (!KEPT_CONTEXTS.contains(key)) {
                contexts.remove(key);
            }
        }
        event.setUser(opaqueUserOnly());
        event.setMessage(templateOnly(event.getMessage()));
        List<SentryException> exceptions = event.getExceptions();
        if (exceptions != null) {
            for (SentryException exception : exceptions) {
                exception.setValue(null);
                if (exception.getMechanism() != null) {
                    exception.getMechanism().setData(null);
                }
            }
        }
        // Stable things only: where the code runs, not who calls it.
        event.setServerName(null);
        String requestId = RequestContext.requestId();
        if (requestId != null) {
            event.setTag("request_id", requestId);
        }
        return event;
    }

    /** Whatever user the SDK or a caller filled in is replaced by the opaque id bound to this request. */
    private static @Nullable User opaqueUserOnly() {
        String id = RequestContext.userId();
        if (!RequestContext.isOpaqueUserId(id)) {
            return null;
        }
        User user = new User();
        user.setId(id);
        return user;
    }

    /** The log template ("Could not read {}") is ours; the values filled into it may not be. */
    private static @Nullable Message templateOnly(@Nullable Message message) {
        if (message == null) {
            return null;
        }
        Message clean = new Message();
        clean.setMessage(message.getMessage());
        return clean;
    }
}
