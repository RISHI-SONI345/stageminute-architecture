package app.stageminute.platform;

import io.sentry.Sentry;
import org.springframework.stereotype.Component;

/** Sends the error to Sentry when Sentry is on; {@link SentryEventScrubber} strips it on the way out. */
@Component
class SentryErrorReporter implements ErrorReporter {

    @Override
    public void report(Throwable error) {
        try {
            Sentry.captureException(error);
        } catch (RuntimeException ignored) {
            // Reporting must never turn one error into two.
        }
    }
}
