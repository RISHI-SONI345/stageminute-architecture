package app.stageminute.practice.internal;

import static app.stageminute.support.LedgerAssertions.assertLedgerMatchesCounters;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.stageminute.account.AccountService;
import app.stageminute.account.NewAccountContext;
import app.stageminute.platform.UserId;
import app.stageminute.support.Concurrently;
import app.stageminute.support.DatabaseTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The SQL building blocks of the quota (AD-11, AD-12, AD-106): the counter row, the conditional update that
 * never reads then writes, and the ledger. Races run as real parallel transactions against Postgres.
 */
class QuotaRepositoriesTest extends DatabaseTest {

    private static final LocalDate DAY = LocalDate.parse("2026-10-28");
    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");

    @Autowired
    AccountService accounts;

    @Autowired
    DailyCountersRepository counters;

    @Autowired
    QuotaLedgerRepository ledger;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    private UserId newUser() {
        return accounts.resolve(
                "user_" + UUID.randomUUID().toString().replace("-", ""),
                new NewAccountContext("device-" + UUID.randomUUID(), "android", "10.9.2.1"));
    }

    /** What a reserve will do in #55: one transaction, the conditional update, and the ledger row only if it won. */
    private boolean reserveOne(UserId user, int cap) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            counters.ensureRow(user, DAY);
            OptionalInt used = counters.tryUseSession(user, DAY, cap);
            if (used.isEmpty()) {
                return false;
            }
            ledger.append(user, DAY, QuotaEventKind.RESERVE, 1, null, null, NOW);
            return true;
        }));
    }

    @Test
    void theFirstCallOfTheDayCreatesTheRowAndTakesTheSession() {
        UserId user = newUser();

        assertThat(reserveOne(user, 1)).isTrue();

        assertThat(counters.sessionsUsed(user, DAY)).isEqualTo(1);
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void theSecondCallOverTheCapTakesNothingAndWritesNoLedgerRow() {
        UserId user = newUser();
        reserveOne(user, 1);

        assertThat(reserveOne(user, 1)).isFalse();

        assertThat(counters.sessionsUsed(user, DAY)).isEqualTo(1);
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void makingTheRowTwiceKeepsOneRowAndTheCount() {
        UserId user = newUser();
        reserveOne(user, 5);

        counters.ensureRow(user, DAY);

        assertThat(counters.sessionsUsed(user, DAY)).isEqualTo(1);
    }

    @Test
    void anotherDayHasItsOwnCount() {
        UserId user = newUser();
        reserveOne(user, 1);

        assertThat(counters.sessionsUsed(user, DAY.plusDays(1))).isZero();
    }

    @Test
    void ofManyParallelCallsForTheLastSessionExactlyOneWins() {
        UserId user = newUser();

        Concurrently.Outcome<Boolean> outcome = Concurrently.run(8, () -> reserveOne(user, 1));

        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.values()).filteredOn(won -> won).hasSize(1);
        assertThat(counters.sessionsUsed(user, DAY)).isEqualTo(1);
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void parallelFirstCallsWithNoRowYetAllGetTheirTurnUpToTheCap() {
        UserId user = newUser(); // no daily_counters row exists: the INSERT ... ON CONFLICT race is covered

        Concurrently.Outcome<Boolean> outcome = Concurrently.run(4, () -> reserveOne(user, 4));

        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.values()).filteredOn(won -> won).hasSize(4);
        assertThat(counters.sessionsUsed(user, DAY)).isEqualTo(4);
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void parallelCallsNeverPassTheCap() {
        UserId user = newUser();

        Concurrently.Outcome<Boolean> outcome = Concurrently.run(8, () -> reserveOne(user, 3));

        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.values()).filteredOn(won -> won).hasSize(3);
        assertThat(counters.sessionsUsed(user, DAY)).isEqualTo(3);
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void everyLedgerKindIsAcceptedByTheDatabase() {
        UserId user = newUser();
        counters.ensureRow(user, DAY);

        for (QuotaEventKind kind : QuotaEventKind.values()) {
            ledger.append(user, DAY, kind, 0, null, "check", NOW);
        }

        assertThat(jdbc.sql("SELECT count(*) FROM quota_events WHERE user_id = :u")
                        .param("u", user.value())
                        .query(Integer.class)
                        .single())
                .isEqualTo(QuotaEventKind.values().length);
    }

    @Test
    void theLedgerCheckSeesALedgerRowWithNoCounter() {
        UserId user = newUser();
        counters.ensureRow(user, DAY);
        ledger.append(user, DAY, QuotaEventKind.RESERVE, 1, null, null, NOW);

        assertThatThrownBy(() -> assertLedgerMatchesCounters(jdbc, user)).isInstanceOf(AssertionError.class);
    }

    @Test
    void theLedgerCheckSeesACounterWithNoLedgerRow() {
        UserId user = newUser();
        counters.ensureRow(user, DAY);
        counters.tryUseSession(user, DAY, 1);

        assertThatThrownBy(() -> assertLedgerMatchesCounters(jdbc, user)).isInstanceOf(AssertionError.class);
    }

    @Test
    void aReleaseAndAGiveBackTakeASessionOffTheLedgerSum() {
        UserId user = newUser();
        reserveOne(user, 2);
        reserveOne(user, 2);
        jdbc.sql("UPDATE daily_counters SET sessions_used = sessions_used - 2 WHERE user_id = :u")
                .param("u", user.value())
                .update();
        ledger.append(user, DAY, QuotaEventKind.RELEASE, -1, null, "discard", NOW);
        ledger.append(user, DAY, QuotaEventKind.GIVE_BACK, -1, null, "server_failure", NOW);

        assertLedgerMatchesCounters(jdbc, user);
    }
}
