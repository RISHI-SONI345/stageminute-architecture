package app.stageminute.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The rules a Clerk session token must pass after its signature is known to be good (AD-53, 11 section 2 Auth):
 * time (exp, nbf), issuer, authorized party (azp) and a subject. Each rule is tested on its own.
 */
class ClerkTokenValidatorsTest {

    private static final String ISSUER = "https://content-redbird-397.clerk.accounts.dev";
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final OAuth2TokenValidator<Jwt> validator = ClerkTokenValidators.create(ISSUER, List.of("my-app"), clock);

    private static Jwt.Builder good() {
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject("user_123")
                .claim("azp", "my-app")
                .issuedAt(NOW)
                .notBefore(NOW)
                .expiresAt(NOW.plusSeconds(60));
    }

    private OAuth2TokenValidatorResult run(Jwt.Builder token) {
        return validator.validate(token.build());
    }

    @Test
    void aGoodTokenPasses() {
        assertThat(run(good()).hasErrors()).isFalse();
    }

    @Test
    void anExpiredTokenIsRefusedAndSaysItExpired() {
        clock.advance(Duration.ofMinutes(2));

        OAuth2TokenValidatorResult result = run(good());

        assertThat(result.getErrors()).extracting("errorCode").contains(ClerkTokenValidators.EXPIRED);
    }

    @Test
    void aFewSecondsOfClockDifferenceAreTolerated() {
        clock.advance(Duration.ofSeconds(63)); // 3 s after exp

        assertThat(run(good()).hasErrors()).isFalse();
    }

    @Test
    void aTokenNotValidYetIsRefused() {
        OAuth2TokenValidatorResult result = run(good().notBefore(NOW.plusSeconds(120)));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).extracting("errorCode").doesNotContain(ClerkTokenValidators.EXPIRED);
    }

    @Test
    void aTokenWithoutExpiryIsRefused() {
        Jwt noExp = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject("user_123")
                .notBefore(NOW)
                .build();

        assertThat(validator.validate(noExp).hasErrors()).isTrue();
    }

    @Test
    void aTokenWithoutNotBeforeIsRefused() {
        Jwt noNbf = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject("user_123")
                .expiresAt(NOW.plusSeconds(60))
                .build();

        assertThat(validator.validate(noNbf).hasErrors()).isTrue();
    }

    @Test
    void aTokenThatLivesLongerThanASessionTokenIsRefused() {
        assertThat(run(good().expiresAt(NOW.plus(Duration.ofHours(1)))).hasErrors())
                .isTrue();
    }

    @Test
    void aTokenWithoutAnIssueTimeIsRefused() {
        Jwt noIat = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject("user_123")
                .claim("azp", "my-app")
                .notBefore(NOW)
                .expiresAt(NOW.plusSeconds(60))
                .build();

        assertThat(validator.validate(noIat).hasErrors()).isTrue();
    }

    @Test
    void aTokenFromAnotherIssuerIsRefused() {
        assertThat(run(good().issuer("https://evil.clerk.accounts.dev")).hasErrors())
                .isTrue();
    }

    @Test
    void aTokenWithoutAnIssuerIsRefused() {
        Jwt noIssuer = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("user_123")
                .notBefore(NOW)
                .expiresAt(NOW.plusSeconds(60))
                .build();

        assertThat(validator.validate(noIssuer).hasErrors()).isTrue();
    }

    @Test
    void aForeignAuthorizedPartyIsRefused() {
        assertThat(run(good().claim("azp", "https://evil.example")).hasErrors()).isTrue();
    }

    @Test
    void aTokenWithoutAuthorizedPartyPasses() {
        Jwt.Builder native_ = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject("user_123")
                .issuedAt(NOW)
                .notBefore(NOW)
                .expiresAt(NOW.plusSeconds(60));

        assertThat(run(native_).hasErrors()).isFalse();
    }

    @Test
    void whenNoPartyIsListedAnyTokenThatNamesOneIsRefused() {
        OAuth2TokenValidator<Jwt> strict = ClerkTokenValidators.create(ISSUER, List.of(), clock);

        assertThat(strict.validate(good().build()).hasErrors()).isTrue();
    }

    @Test
    void aTokenWithoutASubjectIsRefused() {
        Jwt noSub = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .claim("azp", "my-app")
                .notBefore(NOW)
                .expiresAt(NOW.plusSeconds(60))
                .build();

        assertThat(validator.validate(noSub).hasErrors()).isTrue();
        assertThat(Map.of("blank", run(good().subject(" ")).hasErrors())).containsEntry("blank", true);
    }
}
