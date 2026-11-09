package app.stageminute.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * Checks that a webhook really comes from Clerk (Clerk signs with Svix). The sender computes HMAC-SHA-256 over
 * {@code <svix-id>.<svix-timestamp>.<raw body>} with the secret that follows {@code whsec_}, and sends it as
 * {@code v1,<base64>}. During a key change the header holds several signatures; one good one is enough.
 *
 * <p>Two details matter. The body must be the exact bytes received (re-printing the JSON changes them). And
 * the timestamp must be recent, or an attacker could replay an old, genuinely signed message later.
 */
final class SvixSignature {

    private static final String PREFIX = "whsec_";
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] key;

    /** Stops the server with a message that names the variable but never shows its value. */
    SvixSignature(String secret) {
        if (!secret.startsWith(PREFIX)) {
            throw new IllegalStateException("CLERK_WEBHOOK_SECRET must start with whsec_");
        }
        try {
            this.key = Base64.getDecoder().decode(secret.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("CLERK_WEBHOOK_SECRET is not a valid Svix secret");
        }
        if (key.length == 0) {
            throw new IllegalStateException("CLERK_WEBHOOK_SECRET is not a valid Svix secret");
        }
    }

    boolean verify(
            @Nullable String id,
            @Nullable String timestamp,
            byte[] body,
            @Nullable String signatureHeader,
            Instant now,
            Duration tolerance) {
        if (id == null || timestamp == null || signatureHeader == null) {
            return false;
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        Duration age;
        try {
            age = Duration.between(Instant.ofEpochSecond(seconds), now).abs();
        } catch (DateTimeException | ArithmeticException e) {
            return false; // a number too large to be a date is a bad header, not a server error
        }
        if (age.compareTo(tolerance) > 0) {
            return false;
        }
        byte[] expected = sign(id, timestamp, body);
        boolean match = false;
        for (String candidate : signatureHeader.trim().split("\\s+", -1)) {
            if (!candidate.startsWith("v1,")) {
                continue;
            }
            try {
                byte[] given = Base64.getDecoder().decode(candidate.substring(3));
                // Constant time, and every candidate is checked so timing does not say which one matched.
                match |= MessageDigest.isEqual(expected, given);
            } catch (IllegalArgumentException e) {
                // not base64: this candidate cannot match
            }
        }
        return match;
    }

    private byte[] sign(String id, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            mac.update((id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 is not available", e);
        }
    }
}
