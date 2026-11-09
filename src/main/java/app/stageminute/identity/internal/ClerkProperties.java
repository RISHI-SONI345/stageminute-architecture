package app.stageminute.identity.internal;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Clerk settings from the environment (server/.env on this PC; real environment variables on the servers).
 * Nothing here has a default that opens a door: a blank issuer or key address stops the server, a blank
 * party list refuses every token that names a party, and a blank webhook secret turns the webhook off.
 */
@ConfigurationProperties("stageminute.clerk")
record ClerkProperties(String issuer, String jwksUrl, List<String> authorizedParties, String webhookSecret) {

    ClerkProperties {
        authorizedParties = authorizedParties == null
                ? List.of()
                : authorizedParties.stream()
                        .map(String::trim)
                        .filter(party -> !party.isEmpty())
                        .toList();
        webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
    }

    /** Stops the server at startup, naming the variable but never its value, when sign-in cannot be trusted. */
    void requireUsable() {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("CLERK_ISSUER is not set; the server will not start without it");
        }
        if (!issuer.startsWith("https://") || issuer.endsWith("/")) {
            throw new IllegalStateException("CLERK_ISSUER must be an https address without a trailing slash");
        }
        if (jwksUrl == null || jwksUrl.isBlank()) {
            throw new IllegalStateException("CLERK_JWKS_URL is not set; the server will not start without it");
        }
        URI keys = URI.create(jwksUrl);
        boolean https = "https".equals(keys.getScheme());
        // Plain http is allowed only to this machine, so a test can serve its own keys. Anywhere else a
        // network attacker could hand us their keys.
        boolean loopback = "http".equals(keys.getScheme())
                && ("localhost".equals(keys.getHost()) || "127.0.0.1".equals(keys.getHost()));
        if (!https && !loopback) {
            throw new IllegalStateException("CLERK_JWKS_URL must be an https address");
        }
    }
}
