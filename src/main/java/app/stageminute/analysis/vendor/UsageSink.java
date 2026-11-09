package app.stageminute.analysis.vendor;

/**
 * Where the real clients report what each call cost in units (#19.1). The spend meter of #19.2 plugs in here without
 * the clients changing: {@link #starting} is "check before the call" (it may throw {@link VendorUnavailable} to stop
 * the call, which then costs nothing), {@link #used} is "record after", and {@link #noUsageReported} marks a call that
 * may have cost money but told us nothing (a time-out or a dropped connection). Every method has a do-nothing default.
 * Implementations must not log or keep speech; they receive units only.
 */
public interface UsageSink {

    /** Nothing is recorded. */
    UsageSink NONE = new UsageSink() {};

    /**
     * About to call the vendor (also before a retry). May throw {@link VendorUnavailable} to refuse the call.
     *
     * @param audioMs the length of the take in milliseconds (the worst case Deepgram can bill)
     */
    default void starting(Vendor vendor, long audioMs) {}

    /** The vendor answered and reported this use. Called once per answered call, retries included. */
    default void used(VendorUsage usage) {}

    /** The call ended with no usage report (time-out, network error, an answer with no usage field). */
    default void noUsageReported(Vendor vendor) {}
}
