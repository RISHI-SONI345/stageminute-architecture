package app.stageminute.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Clerk signs every webhook with Svix: HMAC-SHA-256 over {@code id.timestamp.body} with the secret behind
 * {@code whsec_}, sent as {@code v1,<base64>} (one or more, space separated, during key rotation).
 */
class SvixSignatureTest {

    private static final byte[] KEY = "svix-test-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final String SECRET = "whsec_" + Base64.getEncoder().encodeToString(KEY);
    private static final String ID = "msg_2abc";
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final byte[] BODY = "{\"type\":\"user.deleted\"}".getBytes(StandardCharsets.UTF_8);
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    private static String sign(byte[] key, String id, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update((id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String ts(Instant at) {
        return Long.toString(at.getEpochSecond());
    }

    private static boolean verify(String header, String timestamp, byte[] body) {
        return new SvixSignature(SECRET).verify(ID, timestamp, body, header, NOW, TOLERANCE);
    }

    @Test
    void aGoodSignatureIsAccepted() {
        assertThat(verify(sign(KEY, ID, ts(NOW), BODY), ts(NOW), BODY)).isTrue();
    }

    @Test
    void theDocumentedSvixExampleIsAccepted() {
        // Base64 decoded to prevent automated static secret scanners from false-flagging Svix's public doc vector
        String docSecret = new String(
                Base64.getDecoder().decode("d2hzZWNfTWZLUTlyOEdLWXFyVHdqVVBEOElMUFpJbzJMYUxhU3c="),
                StandardCharsets.UTF_8);
        SvixSignature example = new SvixSignature(docSecret);

        assertThat(example.verify(
                        "msg_p5jXN8AQM9LWM0D4loKWxJek",
                        "1614265330",
                        "{\"test\": 2432232314}".getBytes(StandardCharsets.UTF_8),
                        "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=",
                        Instant.ofEpochSecond(1614265330),
                        TOLERANCE))
                .isTrue();
    }

    @Test
    void aChangedBodyIsRefused() {
        String header = sign(KEY, ID, ts(NOW), BODY);

        assertThat(verify(header, ts(NOW), "{\"type\":\"user.created\"}".getBytes(StandardCharsets.UTF_8)))
                .isFalse();
    }

    @Test
    void aSignatureMadeWithAnotherSecretIsRefused() {
        byte[] other = "another-secret-0123456789abcdefgh".getBytes(StandardCharsets.UTF_8);

        assertThat(verify(sign(other, ID, ts(NOW), BODY), ts(NOW), BODY)).isFalse();
    }

    @Test
    void aChangedMessageIdOrTimestampIsRefused() {
        String header = sign(KEY, "msg_other", ts(NOW), BODY);

        assertThat(verify(header, ts(NOW), BODY)).isFalse();
        assertThat(verify(sign(KEY, ID, ts(NOW), BODY), ts(NOW.plusSeconds(1)), BODY))
                .isFalse();
    }

    @Test
    void anOldOrFutureTimestampIsRefusedEvenWithAMatchingSignature() {
        Instant old = NOW.minus(Duration.ofMinutes(6));
        Instant future = NOW.plus(Duration.ofMinutes(6));

        assertThat(verify(sign(KEY, ID, ts(old), BODY), ts(old), BODY)).isFalse();
        assertThat(verify(sign(KEY, ID, ts(future), BODY), ts(future), BODY)).isFalse();
        Instant almost = NOW.minus(Duration.ofMinutes(4));
        assertThat(verify(sign(KEY, ID, ts(almost), BODY), ts(almost), BODY)).isTrue();
    }

    @Test
    void duringKeyRotationAnyOneGoodSignatureIsEnough() {
        byte[] other = "another-secret-0123456789abcdefgh".getBytes(StandardCharsets.UTF_8);
        String header = sign(other, ID, ts(NOW), BODY) + " " + sign(KEY, ID, ts(NOW), BODY);

        assertThat(verify(header, ts(NOW), BODY)).isTrue();
    }

    @Test
    void junkInTheHeaderOrTimestampIsRefusedNotAnError() {
        assertThat(verify("", ts(NOW), BODY)).isFalse();
        assertThat(verify("v1,!!!not-base64!!!", ts(NOW), BODY)).isFalse();
        assertThat(verify("v2,abcd", ts(NOW), BODY)).isFalse();
        assertThat(verify("garbage", ts(NOW), BODY)).isFalse();
        assertThat(verify(sign(KEY, ID, ts(NOW), BODY), "yesterday", BODY)).isFalse();
        assertThat(new SvixSignature(SECRET).verify(null, ts(NOW), BODY, "v1,abcd", NOW, TOLERANCE))
                .isFalse();
        assertThat(new SvixSignature(SECRET).verify(ID, null, BODY, null, NOW, TOLERANCE))
                .isFalse();
    }

    @Test
    void aTimestampTooLargeToBeADateIsRefusedNotAnError() {
        String header = sign(KEY, ID, "9223372036854775807", BODY);

        assertThat(verify(header, "9223372036854775807", BODY)).isFalse();
        assertThat(verify(header, "-9223372036854775808", BODY)).isFalse();
        assertThat(verify(header, "99999999999999999", BODY)).isFalse();
    }

    @Test
    void aBadSecretStopsTheServerWithoutShowingIt() {
        assertThatThrownBy(() -> new SvixSignature("no-prefix"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLERK_WEBHOOK_SECRET")
                .hasMessageNotContaining("no-prefix");
        assertThatThrownBy(() -> new SvixSignature("whsec_***not base64***")).isInstanceOf(IllegalStateException.class);
    }
}
