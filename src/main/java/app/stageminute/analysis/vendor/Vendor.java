package app.stageminute.analysis.vendor;

import java.util.Locale;

/** The two outside companies the pipeline calls. The name is the only label a metric or a log line carries for one. */
public enum Vendor {
    DEEPGRAM,
    GEMINI;

    /** The lower-case name used in metric tags and log lines. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
