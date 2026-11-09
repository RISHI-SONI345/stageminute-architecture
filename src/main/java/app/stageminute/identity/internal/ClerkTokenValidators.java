package app.stageminute.identity.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;

/**
 * What a Clerk session token must satisfy besides a good signature (AD-53, 11 section 2 "Auth"):
 *
 * <ul>
 *   <li>{@code exp} and {@code nbf} are present and hold, read against the one injected {@link Clock};
 *   <li>{@code iss} is our Clerk Frontend API address;
 *   <li>{@code azp}, when the token carries one, is a party we listed (a native token may carry none);
 *   <li>{@code sub} (the Clerk user id) is present.
 * </ul>
 *
 * Every rule is a separate validator so a failure names its cause and each can be tested alone.
 */
final class ClerkTokenValidators {

    /** Error code of a token whose time ran out; the entry point turns it into {@code session_expired}. */
    static final String EXPIRED = "token_expired";

    /** Clerk's own SDKs allow 5 seconds for two clocks that disagree a little. */
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(5);

    /** Clerk session tokens live 60 seconds; twice that is the most we accept. */
    private static final Duration MAX_LIFETIME = Duration.ofMinutes(2);

    private ClerkTokenValidators() {}

    static OAuth2TokenValidator<Jwt> create(String issuer, List<String> authorizedParties, Clock clock) {
        return new DelegatingOAuth2TokenValidator<>(
                new TimeValidator(clock),
                new JwtIssuerValidator(issuer),
                new AuthorizedPartyValidator(authorizedParties),
                new SubjectValidator());
    }

    private static OAuth2TokenValidatorResult failure(String code, String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(code, description, null));
    }

    /** Both {@code exp} and {@code nbf} must exist: a token that never expires is not accepted. */
    private record TimeValidator(Clock clock) implements OAuth2TokenValidator<Jwt> {
        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            Instant expires = jwt.getExpiresAt();
            Instant notBefore = jwt.getNotBefore();
            if (expires == null || notBefore == null) {
                return failure(
                        "invalid_token",
                        "The token has no exp or nbf"); // copy-ok: token error text for logs, never shown to a person
            }
            Instant now = clock.instant();
            if (!now.minus(CLOCK_SKEW).isBefore(expires)) {
                return failure(
                        EXPIRED,
                        "The token has expired"); // copy-ok: token error text for logs, never shown to a person
            }
            Instant issued = jwt.getIssuedAt();
            if (issued == null || Duration.between(issued, expires).compareTo(MAX_LIFETIME) > 0) {
                // Clerk session tokens live about 60 seconds. A longer-lived token signed by the same key (a
                // JWT template, for example) is not a session token and must not act as one.
                return failure(
                        "invalid_token",
                        "The token lives longer than a session token"); // copy-ok: token error text for logs, never
                // shown to a person
            }
            if (now.plus(CLOCK_SKEW).isBefore(notBefore)) {
                return failure(
                        "invalid_token",
                        "The token is not valid yet"); // copy-ok: token error text for logs, never shown to a person
            }
            return OAuth2TokenValidatorResult.success();
        }
    }

    /** With no parties listed, a token that names one is refused: fail closed. */
    private record AuthorizedPartyValidator(List<String> allowed) implements OAuth2TokenValidator<Jwt> {
        AuthorizedPartyValidator {
            allowed = List.copyOf(allowed);
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            Object azp = jwt.getClaims().get("azp");
            if (azp == null) {
                return OAuth2TokenValidatorResult.success();
            }
            if (azp instanceof String party && allowed.contains(party)) {
                return OAuth2TokenValidatorResult.success();
            }
            return failure(
                    "invalid_token",
                    "The token is for another application"); // copy-ok: token error text for logs, never shown to a
            // person
        }
    }

    private record SubjectValidator() implements OAuth2TokenValidator<Jwt> {
        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            String subject = jwt.getSubject();
            if (subject == null || subject.isBlank()) {
                return failure(
                        "invalid_token",
                        "The token has no subject"); // copy-ok: token error text for logs, never shown to a person
            }
            return OAuth2TokenValidatorResult.success();
        }
    }
}
