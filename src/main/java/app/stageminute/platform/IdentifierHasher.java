package app.stageminute.platform;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Turns a device id or an IP address into a keyed hash (HMAC-SHA-256 with a secret "pepper") before it touches
 * the database (F-086, 05 F-082). Without the pepper a hash cannot be reversed by trying every IP address.
 *
 * <p>The pepper has no default on purpose: a server without one does not start (fail closed), so there is
 * never a pepper that is public in this repository.
 */
@Component
public class IdentifierHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_PEPPER_LENGTH = 32;

    private final SecretKeySpec key;

    IdentifierHasher(@Value("${stageminute.hash-pepper:}") String pepper) {
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalStateException("STAGEMINUTE_HASH_PEPPER is not set; the server will not start without it");
        }
        if (pepper.length() < MIN_PEPPER_LENGTH) {
            throw new IllegalStateException(
                    "STAGEMINUTE_HASH_PEPPER must be at least " + MIN_PEPPER_LENGTH + " characters");
        }
        this.key = new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public byte[] hash(String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA-256 is not available", e);
        }
    }
}
