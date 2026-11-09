package app.stageminute.account;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.platform.IdentifierHasher;
import app.stageminute.support.Api;
import app.stageminute.support.Api.Reply;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.FakeClerk;
import app.stageminute.support.MutableClock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code DELETE /v1/me} over real HTTP (05 F-082, 06-API, ticket #18.1): one transaction writes the event, the
 * deletion note and removes the account; a repeat answers {@code 401 account_deleted}; a new Clerk user is a new
 * account (EC-010).
 */
class DeletionEndpointTest extends DatabaseTest {

    @Autowired
    Environment env;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    IdentifierHasher hasher;

    private static String clerkId() {
        return "user_" + UUID.randomUUID().toString().replace("-", "");
    }

    private Api as(String clerk) {
        return new Api(Integer.parseInt(env.getRequiredProperty("local.server.port")))
                .with("X-Device-Id", "device-" + UUID.randomUUID())
                .with("X-Platform", "android")
                .bearer(FakeClerk.token(clerk, clock.instant()).sign());
    }

    private int count(String sql, Object... params) {
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        for (int i = 0; i < params.length; i += 2) {
            spec = spec.param((String) params[i], params[i + 1]);
        }
        return spec.query(Integer.class).single();
    }

    private int users(String clerk) {
        return count("SELECT count(*) FROM users WHERE clerk_user_id = :c", "c", clerk);
    }

    private int deletionEvents() {
        return count("SELECT count(*) FROM event_publication WHERE event_type LIKE '%Deletion%Requested'");
    }

    @Test
    void deleteAnswers204AndRemovesTheAccountAndLeavesOneOpaqueNote() {
        String clerk = clerkId();
        Api person = as(clerk);
        assertThat(person.get("/v1/me").status()).isEqualTo(200);
        UUID id = jdbc.sql("SELECT id FROM users WHERE clerk_user_id = :c")
                .param("c", clerk)
                .query(UUID.class)
                .single();

        Reply reply = person.delete("/v1/me");

        assertThat(reply.status()).isEqualTo(204);
        assertThat(users(clerk)).isZero();
        assertThat(count("SELECT count(*) FROM deleted_users WHERE user_id = :u", "u", id))
                .isEqualTo(1);
        // The note holds a keyed hash of the Clerk id, never the id itself (05 section 5).
        assertThat(count(
                        "SELECT count(*) FROM deleted_users WHERE user_id = :u AND clerk_hash = :h",
                        "u",
                        id,
                        "h",
                        hasher.hash(clerk)))
                .isEqualTo(1);
        assertThat(deletionEvents()).isPositive();
    }

    @Test
    void aSecondCallWithTheSameTokenAnswers401AccountDeleted() {
        String clerk = clerkId();
        Api person = as(clerk);
        assertThat(person.get("/v1/me").status()).isEqualTo(200);
        assertThat(person.delete("/v1/me").status()).isEqualTo(204);

        Reply again = person.delete("/v1/me");
        Reply read = person.get("/v1/me");

        assertThat(again.status()).isEqualTo(401);
        assertThat(again.text("$.reason")).isEqualTo("account_deleted");
        assertThat(read.status()).isEqualTo(401);
        assertThat(read.text("$.reason")).isEqualTo("account_deleted");
        assertThat(users(clerk))
                .as("a deleted person's token never makes a new account")
                .isZero();
    }

    @Test
    void aNewClerkUserGetsANewAccountEvenForTheSamePerson() {
        String old = clerkId();
        assertThat(as(old).get("/v1/me").status()).isEqualTo(200);
        assertThat(as(old).delete("/v1/me").status()).isEqualTo(204);

        String fresh = clerkId(); // signing up again at Clerk gives a new Clerk user id (EC-010, EC-134)
        Reply me = as(fresh).get("/v1/me");

        assertThat(me.status()).isEqualTo(200);
        assertThat(users(fresh)).isEqualTo(1);
        assertThat(me.value("$.user.onboarded")).isEqualTo(false);
    }

    @Test
    void ifAnyStepThrowsNothingChangesAndNoEventIsKept() {
        String clerk = clerkId();
        Api person = as(clerk);
        assertThat(person.get("/v1/me").status()).isEqualTo(200);
        int eventsBefore = deletionEvents();
        // Another person's note already holds this Clerk hash, so writing ours breaks the unique rule.
        jdbc.sql("INSERT INTO deleted_users (user_id, deleted_at, clerk_hash) VALUES (:u, :t, :h)")
                .param("u", UUID.randomUUID())
                .param("t", OffsetDateTime.parse("2026-10-19T10:00:00Z"))
                .param("h", hasher.hash(clerk))
                .update();

        Reply reply = person.delete("/v1/me");

        assertThat(reply.status()).isGreaterThanOrEqualTo(500);
        assertThat(users(clerk)).as("the account is still there").isEqualTo(1);
        assertThat(deletionEvents()).as("the outbox row went with the rollback").isEqualTo(eventsBefore);
        assertThat(count("SELECT count(*) FROM deleted_users WHERE clerk_hash = :h", "h", hasher.hash(clerk)))
                .isEqualTo(1);
    }
}
