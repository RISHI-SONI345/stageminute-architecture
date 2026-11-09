package app.stageminute.account;

/**
 * The {@code consent} block of {@code GET /me}. {@code ai} is true only while the person's consent is for the
 * current policy version; {@code needsConsent} makes the app show the consent screen (F-079).
 */
public record ConsentState(boolean ai, String policyVersion, boolean needsConsent, boolean usageData) {}
