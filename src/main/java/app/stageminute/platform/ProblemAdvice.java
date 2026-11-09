package app.stageminute.platform;

import java.net.URI;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error the controllers raise leaves as an RFC 9457 problem (AD-08) that carries the request id as
 * {@code request_id}, so a user report can be matched to one log trail. An error nobody planned for
 * becomes a plain 500 problem: its message is never copied into the response, because it could hold
 * request data. Reason codes for planned errors are added by the modules that raise them.
 */
@RestControllerAdvice
class ProblemAdvice extends ResponseEntityExceptionHandler {

    static final String REQUEST_ID_PROPERTY = "request_id";
    static final String PROBLEM_TYPE_BASE = "https://stageminute.app/problems/";

    private static final Logger LOG = LoggerFactory.getLogger(ProblemAdvice.class);

    private final ErrorReporter reporter;
    private final Settings settings;

    ProblemAdvice(ErrorReporter reporter, Settings settings) {
        this.reporter = reporter;
        this.settings = settings;
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            stamp(problem);
        }
        return response;
    }

    /** A body that is not valid JSON is a validation failure with a reason code, not a bare 400. */
    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return refused(new BusinessRefusal(Reason.VALIDATION_FAILED));
    }

    /** An upload over the multipart limit is a loud 413 with the reason and the limit (06-API section 1). */
    @Override
    protected @Nullable ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return refused(new BusinessRefusal(
                Reason.FILE_TOO_LARGE, Map.of("max_bytes", settings.get(SettingKey.UPLOAD_MAX_BYTES))));
    }

    /** A planned refusal: the status and title come from {@link Reason}; the app reads {@code reason}. */
    @ExceptionHandler(BusinessRefusal.class)
    ResponseEntity<Object> refused(BusinessRefusal refusal) {
        Reason reason = refusal.reason();
        ProblemDetail problem = ProblemDetail.forStatus(reason.status());
        problem.setType(URI.create(PROBLEM_TYPE_BASE + reason.code()));
        problem.setTitle(reason.title());
        problem.setProperty("reason", reason.code());
        refusal.extra().forEach(problem::setProperty);
        stamp(problem);
        return ResponseEntity.status(reason.status()).body(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unplanned(Exception ex, WebRequest request) {
        LOG.error("Unplanned error", StackOnly.of(ex));
        reporter.report(ex);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        stamp(problem);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    private static void stamp(ProblemDetail problem) {
        String id = RequestContext.requestId();
        if (id != null) {
            problem.setProperty(REQUEST_ID_PROPERTY, id);
        }
    }

    /**
     * A copy of an exception that keeps the class names and the stack but none of the messages, because a
     * message may hold request data (F-081). The real exception goes to {@link ErrorReporter};
     * {@link SentryEventScrubber} drops the Sentry event this log line would make, so there is one event.
     */
    static final class StackOnly extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private StackOnly(String type, StackTraceElement[] stack, @Nullable Throwable cause) {
            super(type, cause, false, true);
            setStackTrace(stack);
        }

        private static final int MAX_DEPTH = 10;

        static StackOnly of(Throwable original) {
            return of(original, 0);
        }

        private static StackOnly of(Throwable original, int depth) {
            Throwable cause = original.getCause();
            return new StackOnly(
                    original.getClass().getName(),
                    original.getStackTrace(),
                    cause == null || depth >= MAX_DEPTH ? null : of(cause, depth + 1));
        }
    }
}
