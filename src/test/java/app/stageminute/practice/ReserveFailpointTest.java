package app.stageminute.practice;

import static app.stageminute.support.LedgerAssertions.assertLedgerMatchesCounters;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import app.stageminute.account.AccountService;
import app.stageminute.account.NewAccountContext;
import app.stageminute.platform.UserId;
import app.stageminute.practice.internal.QuotaEventKind;
import app.stageminute.practice.internal.QuotaLedgerRepository;
import app.stageminute.practice.internal.SessionsRepository;
import app.stageminute.support.DatabaseTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Failpoints between the steps of a reserve (ticket #55): a crash at any point after the counter moved
 * leaves no counter change, no session row and no ledger row, because the steps share one transaction.
 * The spies wrap the real repositories and throw at one chosen step.
 */
class ReserveFailpointTest extends DatabaseTest {

    private static final String TOPIC = "prep-ele-001";

    @MockitoSpyBean
    SessionsRepository sessions;

    @MockitoSpyBean
    QuotaLedgerRepository ledger;

    @Autowired
    Practice practice;

    @Autowired
    AccountService accounts;

    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void stopFailing() {
        reset(sessions, ledger);
    }

    private UserId newUser() {
        return accounts.resolve(
                "user_" + UUID.randomUUID().toString().replace("-", ""),
                new NewAccountContext("device-" + UUID.randomUUID(), "android", "10.9.2.1"));
    }

    private void assertNothingWritten(UserId user) {
        assertThat(jdbc.sql("SELECT coalesce(sum(sessions_used), 0) FROM daily_counters WHERE user_id = :u")
                        .param("u", user.value())
                        .query(Integer.class)
                        .single())
                .as("sessions_used")
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM practice_sessions WHERE user_id = :u")
                        .param("u", user.value())
                        .query(Integer.class)
                        .single())
                .as("session rows")
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM quota_events WHERE user_id = :u")
                        .param("u", user.value())
                        .query(Integer.class)
                        .single())
                .as("ledger rows")
                .isZero();
        assertLedgerMatchesCounters(jdbc, user);
    }

    @Test
    void aCrashAfterTheCounterUpdateAndBeforeTheSessionRowLeavesNothing() {
        UserId user = newUser();
        doThrow(new IllegalStateException("failpoint"))
                .when(sessions)
                .insertReserved(any(), any(), any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> practice.reserve(user, "t_fail_1", TOPIC, null))
                .hasMessage("failpoint");

        assertNothingWritten(user);
    }

    @Test
    void aCrashAfterTheCounterUpdateAndBeforeTheLedgerInsertLeavesNothing() {
        UserId user = newUser();
        doThrow(new IllegalStateException("failpoint"))
                .when(ledger)
                .append(any(), any(), any(QuotaEventKind.class), anyInt(), any(), any(), any());

        assertThatThrownBy(() -> practice.reserve(user, "t_fail_2", TOPIC, null))
                .hasMessage("failpoint");

        assertNothingWritten(user);
    }

    @Test
    void aCrashAfterTheSessionRowAndTheLedgerRowAndBeforeTheResponseLeavesNothing() {
        UserId user = newUser();
        doAnswer(call -> {
                    call.callRealMethod();
                    throw new IllegalStateException("failpoint");
                })
                .when(ledger)
                .append(any(), any(), any(QuotaEventKind.class), anyInt(), any(), any(), any());

        assertThatThrownBy(() -> practice.reserve(user, "t_fail_3", TOPIC, null))
                .hasMessage("failpoint");

        assertNothingWritten(user);
    }

    @Test
    void afterACrashTheSameTakeCanBeReservedCleanly() {
        UserId user = newUser();
        doThrow(new IllegalStateException("failpoint"))
                .when(ledger)
                .append(any(), any(), any(QuotaEventKind.class), anyInt(), any(), any(), any());
        assertThatThrownBy(() -> practice.reserve(user, "t_fail_4", TOPIC, null))
                .hasMessage("failpoint");
        reset(ledger);

        Reservation retry = practice.reserve(user, "t_fail_4", TOPIC, null);

        assertThat(retry.state()).isEqualTo(QuotaState.OK);
        assertThat(retry.repeated()).isFalse();
        assertLedgerMatchesCounters(jdbc, user);
    }
}
