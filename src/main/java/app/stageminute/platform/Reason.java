package app.stageminute.platform;

import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * Every reason code the server can send (requirements/03 section 2, docs/architecture/06-API.md section 1).
 * The app maps each code to a copy key; the server never sends a sentence for the user.
 */
public enum Reason {
    QUOTA_ZERO(
            HttpStatus.FORBIDDEN,
            "No session left today"), // copy-ok: problem title for developers; the app maps the code to a copy key
    PRO_CAP(
            HttpStatus.TOO_MANY_REQUESTS,
            "Daily Pro limit reached"), // copy-ok: problem title for developers; the app maps the code to a copy key
    THROTTLED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Feedback is paused"), // copy-ok: problem title for developers; the app maps the code to a copy key
    CONSENT_OFF(
            HttpStatus.FORBIDDEN,
            "AI analysis consent is off"), // copy-ok: problem title for developers; the app maps the code to a copy key
    TOO_SHORT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "Take too short"), // copy-ok: problem title for developers; the app maps the code to a copy key
    FILE_TOO_LARGE(
            HttpStatus.CONTENT_TOO_LARGE,
            "Recording too large"), // copy-ok: problem title for developers; the app maps the code to a copy key
    UNSUPPORTED_FORMAT(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "Unsupported recording format"), // copy-ok: problem title for developers; the app maps the code to a copy
    // key
    RATE_LIMITED(
            HttpStatus.TOO_MANY_REQUESTS,
            "Too many requests"), // copy-ok: problem title for developers; the app maps the code to a copy key
    ANALYSIS_UNAVAILABLE(
            HttpStatus.BAD_GATEWAY,
            "Analysis unavailable"), // copy-ok: problem title for developers; the app maps the code to a copy key
    SESSION_EXPIRED(
            HttpStatus.UNAUTHORIZED,
            "Sign-in session ended"), // copy-ok: problem title for developers; the app maps the code to a copy key
    ACCOUNT_DELETED(
            HttpStatus.UNAUTHORIZED,
            "Account deleted"), // copy-ok: problem title for developers; the app maps the code to a copy key
    UPDATE_REQUIRED(
            HttpStatus.UPGRADE_REQUIRED,
            "App update required"), // copy-ok: problem title for developers; the app maps the code to a copy key
    AD_NOT_AVAILABLE(
            HttpStatus.CONFLICT,
            "Ad not available"), // copy-ok: problem title for developers; the app maps the code to a copy key
    ATTEMPT_NOT_ALLOWED(
            HttpStatus.CONFLICT,
            "Attempt not allowed"), // copy-ok: problem title for developers; the app maps the code to a copy key
    NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "Not found"), // copy-ok: problem title for developers; the app maps the code to a copy key
    VALIDATION_FAILED(
            HttpStatus.BAD_REQUEST,
            "Validation failed"); // copy-ok: problem title for developers; the app maps the code to a copy key

    private final HttpStatus status;
    private final String title;

    Reason(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    /** The wire value, for example {@code quota_zero}. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public HttpStatus status() {
        return status;
    }

    /** For logs and support only; the app shows the copy mapped from {@link #code()}. */
    public String title() {
        return title;
    }
}
