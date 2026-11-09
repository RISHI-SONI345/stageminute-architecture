package app.stageminute.analysis.vendor;

/**
 * The numbers the real clients obey. In production they come from the server settings by name
 * ({@code SettingsVendorLimits}); a test passes its own. Time values are milliseconds.
 */
public interface VendorLimits {

    /** The server's total time for one analysis (03 F-122: 45 s). */
    int budgetMs();

    /** A retry runs only when at least this much of the budget is left. */
    int retryMinRemainingMs();

    /** Breaker: the share of failed calls, in percent, that opens it. */
    int breakerFailurePercent();

    /** Breaker: how many of the latest calls it looks at. */
    int breakerWindowCalls();

    /** Breaker: fewest calls in the window before it may open. */
    int breakerMinCalls();

    /** Breaker: seconds it stays open before it lets a few calls through. */
    int breakerOpenSeconds();

    /** Breaker: calls let through while half open. */
    int breakerHalfOpenCalls();

    /** Bulkhead: most calls to one vendor at the same time. */
    int bulkheadMaxCalls();

    /** Gemini: tokens it may spend thinking; 0 means the request names none and the model default applies. */
    int geminiThinkingBudgetTokens();
}
