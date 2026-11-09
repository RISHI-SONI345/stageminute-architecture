package app.stageminute.platform;

/**
 * Where unplanned errors are reported besides the log (AD-03: a vendor sits behind an interface). The real
 * adapter is {@link SentryErrorReporter}; tests use a fake.
 */
interface ErrorReporter {

    /** Reports an error the code did not plan for. Never throws. */
    void report(Throwable error);
}
