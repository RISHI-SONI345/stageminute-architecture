package app.stageminute.platform;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

/**
 * The transcript of a take (the glossary's {@code speech_data}). It lives on the phone; the server holds it
 * only in memory for one request and the 10-minute retry cache. This wrapper makes a leak hard to do by
 * accident: it prints as {@code [redacted]} in logs, string joins and exception messages, and it refuses
 * to become JSON. The one way to read the text is {@link #reveal()}, which the analysis code calls on
 * purpose (AD-23, 05 F-081).
 */
public final class SpeechData {

    public static final String REDACTED = "[redacted]";

    private final String text;

    public SpeechData(String text) {
        this.text = Objects.requireNonNull(text);
    }

    /** The text itself. Never pass the result to a logger, a metric, an error or Sentry. */
    public String reveal() {
        return text;
    }

    @Override
    public String toString() {
        return REDACTED;
    }

    /** Jackson calls this to write the value; it fails instead, and the message holds no text. */
    @SuppressWarnings("DoNotCallSuggester") // Jackson must call it; the failure is the point
    @JsonValue
    Object refuseJson() {
        throw new UnsupportedOperationException("SpeechData must never be written as JSON");
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SpeechData that && text.equals(that.text);
    }

    @Override
    public int hashCode() {
        // Constant on purpose: a hash of the text would leak it into hash-based logs and metrics.
        return SpeechData.class.hashCode();
    }
}
