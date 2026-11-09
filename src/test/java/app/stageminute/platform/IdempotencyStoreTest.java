package app.stageminute.platform;

import static org.assertj.core.api.Assertions.assertThat;

import app.stageminute.platform.IdempotencyStore.Begin;
import app.stageminute.platform.IdempotencyStore.Outcome;
import app.stageminute.support.DatabaseTest;
import app.stageminute.support.MutableClock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Idempotent writes (server/CLAUDE.md "Rules"): the second try of the same request never runs it twice. */
class IdempotencyStoreTest extends DatabaseTest {

    @Autowired
    IdempotencyStore store;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    private UUID newUser() {
        UUID id = UUID.randomUUID();
        OffsetDateTime t = OffsetDateTime.parse("2026-10-19T10:00:00Z");
        jdbc.sql("INSERT INTO users (id, clerk_user_id, level, style, timezone, last_sign_in_at, created_at)"
                        + " VALUES (:id, :c, 'starter', 'prep', 'UTC', :t, :t)")
                .param("id", id)
                .param("c", "user_" + id)
                .param("t", t)
                .update();
        return id;
    }

    private static byte[] hash(int n) {
        return new byte[] {(byte) n};
    }

    @Test
    void theFirstTryStartsAndTheSecondSeesItInProgress() {
        UUID u = newUser();

        assertThat(store.begin(u, "sessions:take-1", hash(1)).outcome()).isEqualTo(Outcome.STARTED);
        assertThat(store.begin(u, "sessions:take-1", hash(1)).outcome()).isEqualTo(Outcome.IN_PROGRESS);
    }

    @Test
    void aFinishedRequestReturnsTheStoredAnswer() {
        UUID u = newUser();
        store.begin(u, "analyze:take-1", hash(1));
        store.complete(u, "analyze:take-1", "{\"score\":80}");

        Begin again = store.begin(u, "analyze:take-1", hash(1));

        assertThat(again.outcome()).isEqualTo(Outcome.DONE);
        assertThat(again.response()).contains("\"score\"");
    }

    @Test
    void theSameKeyWithADifferentRequestIsAConflict() {
        UUID u = newUser();
        store.begin(u, "k", hash(1));

        assertThat(store.begin(u, "k", hash(2)).outcome()).isEqualTo(Outcome.CONFLICT);
    }

    @Test
    void aFailedRequestMayBeTriedAgain() {
        UUID u = newUser();
        store.begin(u, "k", hash(1));
        store.fail(u, "k");

        assertThat(store.begin(u, "k", hash(1)).outcome()).isEqualTo(Outcome.STARTED);
    }

    @Test
    void twoPeopleMayUseTheSameKey() {
        assertThat(store.begin(newUser(), "k", hash(1)).outcome()).isEqualTo(Outcome.STARTED);
        assertThat(store.begin(newUser(), "k", hash(1)).outcome()).isEqualTo(Outcome.STARTED);
    }

    @Test
    void anEntryOlderThanTheTimeLimitCountsAsNew() {
        UUID u = newUser();
        store.begin(u, "k", hash(1));
        store.complete(u, "k", "{}");

        clock.advance(Duration.ofMinutes(11)); // IDEMPOTENCY_TTL_MINUTES is 10

        assertThat(store.begin(u, "k", hash(1)).outcome()).isEqualTo(Outcome.STARTED);
    }

    @Test
    void purgeRemovesOnlyEntriesPastTheTimeLimit() {
        UUID u = newUser();
        store.begin(u, "old", hash(1));
        clock.advance(Duration.ofMinutes(11));
        store.begin(u, "fresh", hash(1));

        int removed = store.purgeExpired();

        assertThat(removed).isPositive();
        assertThat(jdbc.sql("SELECT key FROM idempotency_entries WHERE user_id = :u")
                        .param("u", u)
                        .query(String.class)
                        .list())
                .containsExactly("fresh");
    }
}
