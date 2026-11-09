/**
 * Shared kernel: errors, settings and flags, idempotency, rate limits, the clock, single-run jobs.
 * Every module may use it; it uses no other module.
 * <p>Start here: {@code Settings}, {@code Flags}, {@code Reason}, {@code ProblemAdvice}, {@code TimeConfig}, {@code DailyCaps} (a daily cap counted by a module that does not own {@code daily_counters}; {@code practice} answers it), {@code SpendGate} and {@code SpendDecision} (the AI-cost throttle question {@code practice} asks; {@code analysis.spend.SpendMeter} answers), {@code VendorHttp} (how a vendor's delete is called), {@code EventRetryJob} (tries failed event listeners again). Map: ARCHITECTURE.md.
 */
@ApplicationModule(displayName = "platform", type = ApplicationModule.Type.OPEN)
package app.stageminute.platform;

import org.springframework.modulith.ApplicationModule;
