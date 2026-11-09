package app.stageminute.analysis.vendor;

/** Limits for the vendor client tests: the launch values, with the few a test needs to change. */
record TestLimits(int budgetMs, int retryMinRemainingMs, int breakerMinCalls, int bulkheadMaxCalls, int thinkingBudget)
        implements VendorLimits {

    static TestLimits launch() {
        return new TestLimits(45_000, 10_000, 10, 8, 0);
    }

    TestLimits withBudgetMs(int ms) {
        return new TestLimits(ms, retryMinRemainingMs, breakerMinCalls, bulkheadMaxCalls, thinkingBudget);
    }

    TestLimits withRetryMinRemainingMs(int ms) {
        return new TestLimits(budgetMs, ms, breakerMinCalls, bulkheadMaxCalls, thinkingBudget);
    }

    TestLimits withBreakerMinCalls(int calls) {
        return new TestLimits(budgetMs, retryMinRemainingMs, calls, bulkheadMaxCalls, thinkingBudget);
    }

    TestLimits withBulkheadMaxCalls(int calls) {
        return new TestLimits(budgetMs, retryMinRemainingMs, breakerMinCalls, calls, thinkingBudget);
    }

    TestLimits withThinkingBudget(int tokens) {
        return new TestLimits(budgetMs, retryMinRemainingMs, breakerMinCalls, bulkheadMaxCalls, tokens);
    }

    @Override
    public int geminiThinkingBudgetTokens() {
        return thinkingBudget;
    }

    @Override
    public int breakerFailurePercent() {
        return 50;
    }

    @Override
    public int breakerWindowCalls() {
        return 20;
    }

    @Override
    public int breakerOpenSeconds() {
        return 30;
    }

    @Override
    public int breakerHalfOpenCalls() {
        return 3;
    }
}
