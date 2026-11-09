package app.stageminute.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.support.Api;
import app.stageminute.support.Api.Reply;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.FakeClerk;
import app.stageminute.support.MutableClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Ticket #8 acceptance: tokens with a wrong {@code iss}, a bad signature, an expired time or a foreign
 * {@code azp} are refused; parallel first calls make one {@code users} row (11 section 2 "Auth").
 * Everything goes over real HTTP to the running server, which fetches the public keys from {@link FakeClerk}.
 */
class ClerkAuthenticationTest extends DatabaseTest {

    @Autowired
    Environment env;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    private Api api() {
        return new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")))
                .with("X-Device-Id", "device-" + UUID.randomUUID())
                .with("X-Platform", "android");
    }

    private static String clerkId() {
        return "user_" + UUID.randomUUID().toString().replace("-", "");
    }

    private FakeClerk.Token token(String clerk) {
        return FakeClerk.token(clerk, clock.instant());
    }

    private int rows(String clerk) {
        return jdbc.sql("SELECT count(*) FROM users WHERE clerk_user_id = :c")
                .param("c", clerk)
                .query(Integer.class)
                .single();
    }

    private void assertRefused(Reply reply) {
        assertThat(reply.status()).isEqualTo(401);
        assertThat(reply.text("$.reason")).isEqualTo("session_expired");
        assertThat(reply.header("Content-Type")).contains("application/problem+json");
        assertThat(reply.header("WWW-Authenticate")).startsWith("Bearer");
        assertThat(reply.text("$.request_id")).isNotBlank();
        assertThat(reply.body())
                .doesNotContain("Jwt")
                .doesNotContain("signature")
                .doesNotContain("kid");
    }

    @Test
    void aGoodTokenIsAcceptedAndCreatesTheUserOnTheFirstCall() {
        String clerk = clerkId();

        Reply reply = api().bearer(token(clerk).sign()).get("/v1/me");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(rows(clerk)).isEqualTo(1);
        assertThat(reply.text("$.user.id")).isNotBlank();
    }

    @Test
    void aTokenWithoutAnAuthorizedPartyIsAccepted() {
        assertThat(api().bearer(token(clerkId()).authorizedParty(null).sign())
                        .get("/v1/me")
                        .status())
                .isEqualTo(200);
    }

    @Test
    void noTokenIsRefused() {
        assertRefused(api().get("/v1/me"));
    }

    @Test
    void garbageInPlaceOfATokenIsRefused() {
        assertRefused(api().bearer("not-a-token").get("/v1/me"));
        assertRefused(api().with("Authorization", "Basic dXNlcjpwYXNz").get("/v1/me"));
    }

    @Test
    void anExpiredTokenIsRefusedAndTheAppIsToldItExpired() {
        Instant now = clock.instant();
        Reply reply = api().bearer(token(clerkId())
                        .issuedAt(now.minus(Duration.ofMinutes(3)))
                        .notBefore(now.minus(Duration.ofMinutes(3)))
                        .expires(now.minus(Duration.ofMinutes(1)))
                        .sign())
                .get("/v1/me");

        assertRefused(reply);
        assertThat(reply.text("$.cause")).isEqualTo("expired");
    }

    @Test
    void aTokenNotValidYetIsRefused() {
        Instant later = clock.instant().plus(Duration.ofMinutes(5));

        assertRefused(api().bearer(token(clerkId())
                        .notBefore(later)
                        .expires(later.plusSeconds(60))
                        .sign())
                .get("/v1/me"));
    }

    @Test
    void aTokenWithoutExpiryIsRefused() {
        assertRefused(api().bearer(token(clerkId()).withoutExpiry().sign()).get("/v1/me"));
    }

    @Test
    void aTokenFromAnotherIssuerIsRefusedAndMakesNoUser() {
        String clerk = clerkId();

        assertRefused(api().bearer(
                        token(clerk).issuer("https://evil.clerk.accounts.dev").sign())
                .get("/v1/me"));

        assertThat(rows(clerk)).isZero();
    }

    @Test
    void aTokenSignedByAKeyClerkNeverPublishedIsRefusedAndMakesNoUser() {
        String clerk = clerkId();

        assertRefused(api().bearer(token(clerk).signedByAnUnknownKey().sign()).get("/v1/me"));

        assertThat(rows(clerk)).isZero();
    }

    @Test
    void aTokenForAnotherApplicationIsRefused() {
        assertRefused(api().bearer(
                        token(clerkId()).authorizedParty("https://evil.example").sign())
                .get("/v1/me"));
    }

    @Test
    void anUnsignedTokenIsRefused() {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(("{\"sub\":\"user_x\",\"iss\":\"" + FakeClerk.ISSUER + "\",\"exp\":"
                        + (clock.instant().getEpochSecond() + 60) + ",\"nbf\":"
                        + clock.instant().getEpochSecond()
                        + "}")
                .getBytes(StandardCharsets.UTF_8));

        assertRefused(api().bearer(header + "." + payload + ".").get("/v1/me"));
    }

    // A hang here once held the whole CI job until its 20-minute limit; a limit makes it a plain failure.
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test
    void parallelFirstCallsMakeExactlyOneUserRow() throws Exception {
        String clerk = clerkId();
        String token = token(clerk).sign();
        Api caller = api().bearer(token);
        int callers = 12;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<Reply>> replies = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                replies.add(pool.submit(() -> {
                    go.await();
                    return caller.get("/v1/me");
                }));
            }
            go.countDown();
            List<String> ids = new ArrayList<>();
            for (Future<Reply> future : replies) {
                Reply reply = future.get(45, TimeUnit.SECONDS);
                assertThat(reply.status()).isEqualTo(200);
                ids.add(reply.text("$.user.id"));
            }
            assertThat(ids).containsOnly(ids.get(0));
        } finally {
            pool.shutdownNow();
        }

        assertThat(rows(clerk)).isEqualTo(1);
    }

    @Test
    void healthStaysPublicAndTheApiDocsStayClosed() {
        assertThat(api().get("/actuator/health").status()).isEqualTo(200);
        assertThat(api().get("/v3/api-docs").status()).isIn(401, 404);
        assertThat(api().get("/v1/anything-else").status()).isEqualTo(401);
    }
}
