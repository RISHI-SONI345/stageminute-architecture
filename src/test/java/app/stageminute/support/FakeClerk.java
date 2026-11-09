package app.stageminute.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Stands in for Clerk in tests: a tiny web server that publishes a public key set (the JWKS, as Clerk does at
 * {@code /.well-known/jwks.json}) and a way to sign session tokens with the matching private key. The server
 * under test checks the signature over real HTTP, so the whole verification path runs, not a shortcut.
 *
 * <p>{@code OTHER_KEY} is a second key Clerk never published: a token signed with it must be refused.
 */
public final class FakeClerk {

    public static final String ISSUER = "https://clerk.stageminute.test";
    public static final String AUTHORIZED_PARTY = "stageminute-test-app";
    public static final String WEBHOOK_SECRET = new String(
            java.util.Base64.getDecoder().decode("d2hzZWNfYzNScFoyVnRhdTEwZExsM1pXRmlhMThyTFhObFkzSmxkQzB3TURBd01EQXdNRGVu"),
            java.nio.charset.StandardCharsets.UTF_8);

    private static final RSAKey PUBLISHED_KEY;
    private static final RSAKey OTHER_KEY;
    private static final String JWKS_URL;

    static {
        try {
            PUBLISHED_KEY = new RSAKeyGenerator(2048)
                    .keyID("test-key-1")
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .generate();
            OTHER_KEY = new RSAKeyGenerator(2048)
                    .keyID("test-key-1") // same key id on purpose: only the signature tells it apart
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .generate();
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            byte[] body = new JWKSet(PUBLISHED_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/.well-known/jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.setExecutor(null);
            server.start();
            JWKS_URL = "http://localhost:" + server.getAddress().getPort() + "/.well-known/jwks.json";
        } catch (JOSEException | IOException e) {
            throw new IllegalStateException("Could not start the fake Clerk", e);
        }
    }

    private FakeClerk() {}

    /** The {@code svix-signature} header Clerk would send for this message. */
    public static String svixSignature(String messageId, String timestamp, String body) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            byte[] key = java.util.Base64.getDecoder().decode(WEBHOOK_SECRET.substring("whsec_".length()));
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            mac.update((messageId + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return "v1,"
                    + java.util.Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String jwksUrl() {
        return JWKS_URL;
    }

    /** A token that is valid right now at {@code now}; change what you want to break, then {@link Token#sign()}. */
    public static Token token(String clerkUserId, Instant now) {
        return new Token(clerkUserId, now);
    }

    public static final class Token {
        private String subject;
        private String issuer = ISSUER;
        private String authorizedParty = AUTHORIZED_PARTY;
        private Instant issuedAt;
        private Instant notBefore;
        private Instant expires;
        private RSAKey signingKey = PUBLISHED_KEY;
        private JWSAlgorithm algorithm = JWSAlgorithm.RS256;

        private Token(String subject, Instant now) {
            this.subject = subject;
            this.issuedAt = now;
            this.notBefore = now;
            this.expires = now.plus(Duration.ofSeconds(60)); // Clerk session tokens live 60 seconds
        }

        public Token subject(String value) {
            this.subject = value;
            return this;
        }

        public Token issuer(String value) {
            this.issuer = value;
            return this;
        }

        /** {@code null} leaves the claim out, as a native token may. */
        public Token authorizedParty(String value) {
            this.authorizedParty = value;
            return this;
        }

        public Token issuedAt(Instant value) {
            this.issuedAt = value;
            return this;
        }

        public Token notBefore(Instant value) {
            this.notBefore = value;
            return this;
        }

        public Token expires(Instant value) {
            this.expires = value;
            return this;
        }

        public Token withoutExpiry() {
            this.expires = null;
            return this;
        }

        public Token withoutNotBefore() {
            this.notBefore = null;
            return this;
        }

        /** Signs with a key Clerk never published. */
        public Token signedByAnUnknownKey() {
            this.signingKey = OTHER_KEY;
            return this;
        }

        public String sign() {
            try {
                JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                        .jwtID(UUID.randomUUID().toString())
                        .issuer(issuer)
                        .issueTime(Date.from(issuedAt));
                if (subject != null) {
                    claims.subject(subject);
                }
                if (authorizedParty != null) {
                    claims.claim("azp", authorizedParty);
                }
                if (notBefore != null) {
                    claims.notBeforeTime(Date.from(notBefore));
                }
                if (expires != null) {
                    claims.expirationTime(Date.from(expires));
                }
                SignedJWT jwt = new SignedJWT(
                        new JWSHeader.Builder(algorithm)
                                .keyID(signingKey.getKeyID())
                                .build(),
                        claims.build());
                jwt.sign(new RSASSASigner(signingKey));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException("Could not sign the test token", e);
            }
        }
    }
}
